// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Limited.

#include "exec/pipeline/exchange/sink_buffer.h"

namespace starrocks::pipeline {

SinkBuffer::SinkBuffer(FragmentContext* fragment_ctx, const std::vector<TPlanFragmentDestination>& destinations,
                       bool is_dest_merge, size_t num_sinkers)
        : _fragment_ctx(fragment_ctx),
          _mem_tracker(fragment_ctx->runtime_state()->instance_mem_tracker()),
          _brpc_timeout_ms(std::min(3600, fragment_ctx->runtime_state()->query_options().query_timeout) * 1000),
          _is_dest_merge(is_dest_merge),
          _num_uncancelled_sinkers(num_sinkers) {
    for (const auto& dest : destinations) {
        const auto& instance_id = dest.fragment_instance_id;
        // instance_id.lo == -1 indicates that the destination is pseudo for bucket shuffle join.
        if (instance_id.lo == -1) {
            continue;
        }

        auto it = _num_sinkers.find(instance_id.lo);
        if (it != _num_sinkers.end()) {
            it->second += num_sinkers;
        } else {
            _num_sinkers[instance_id.lo] = num_sinkers;

            _request_seqs[instance_id.lo] = 0;
            // request_seq starts from 0, so the max_continuous_acked_seq should be -1
            _max_continuous_acked_seqs[instance_id.lo] = -1;
            _discontinuous_acked_seqs[instance_id.lo] = std::unordered_set<int64_t>();
            _buffers[instance_id.lo] = std::queue<TransmitChunkInfo, std::list<TransmitChunkInfo>>();
            _num_finished_rpcs[instance_id.lo] = 0;
            _num_in_flight_rpcs[instance_id.lo] = 0;
            _network_time[instance_id.lo] = 0;
            _mutexes[instance_id.lo] = std::make_unique<std::mutex>();

            PUniqueId finst_id;
            finst_id.set_hi(instance_id.hi);
            finst_id.set_lo(instance_id.lo);
            _instance_id2finst_id[instance_id.lo] = std::move(finst_id);
        }
    }

    _num_remaining_eos = 0;
    for (auto& [_, num] : _num_sinkers) {
        _num_remaining_eos += num;
    }
}

SinkBuffer::~SinkBuffer() {
    // In some extreme cases, the pipeline driver has not been created yet, and the query is over
    // At this time, sink_buffer also needs to be able to be destructed correctly
    _is_finishing = true;

    DCHECK(is_finished());

    for (auto& [_, buffer] : _buffers) {
        while (!buffer.empty()) {
            auto& request = buffer.front();
            // Once the request is added to SinkBuffer, its ownership will also be transferred,
            // so SinkBuffer needs to be responsible for the release of resources
            request.params->release_finst_id();
            buffer.pop();
        }
    }
}

void SinkBuffer::add_request(const TransmitChunkInfo& request) {
    DCHECK(_num_remaining_eos > 0);
    if (_is_finishing) {
        // Once the request is added to SinkBuffer, its ownership will also be transferred,
        // so SinkBuffer needs to be responsible for the release of resources
        request.params->release_finst_id();
        return;
    }
    {
        auto& instance_id = request.fragment_instance_id;
        std::lock_guard<std::mutex> l(*_mutexes[instance_id.lo]);
        _buffers[instance_id.lo].push(request);
        _try_to_send_rpc(instance_id);
    }
}

bool SinkBuffer::is_full() const {
    // std::queue' read is concurrent safe without mutex
    // Judgement may not that accurate because we do not known in advance which
    // instance the data to be sent corresponds to
    size_t max_buffer_size = config::pipeline_sink_buffer_size * _buffers.size();
    size_t buffer_size = 0;
    for (auto& [_, buffer] : _buffers) {
        buffer_size += buffer.size();
    }
    return buffer_size > max_buffer_size;
}

bool SinkBuffer::is_finished() const {
    if (!_is_finishing) {
        return false;
    }

    return _num_sending_rpc == 0 && _total_in_flight_rpc == 0;
}

int64_t SinkBuffer::network_time() {
    int64_t max = 0;
    for (auto& [_, overhead] : _network_time) {
        if (overhead > max) {
            max = overhead;
        }
    }
    return max;
}

// When all the ExchangeSinkOperator shared this SinkBuffer are cancelled,
// the rest chunk request and EOS request needn't be sent anymore.
void SinkBuffer::cancel_one_sinker() {
    if (--_num_uncancelled_sinkers == 0) {
        _is_finishing = true;
    }
}

void SinkBuffer::_update_network_time(const TUniqueId& instance_id, const int64_t send_timestamp,
                                      const int64_t receive_timestamp) {
    _network_time[instance_id.lo] += (receive_timestamp - send_timestamp);
}

void SinkBuffer::_process_send_window(const TUniqueId& instance_id, const int64_t sequence) {
    // Both sender side and receiver side can tolerate disorder of tranmission
    // if receiver side is not ExchangeMergeSortSourceOperator
    if (!_is_dest_merge) {
        return;
    }
    auto& seqs = _discontinuous_acked_seqs[instance_id.lo];
    seqs.insert(sequence);
    auto& max_continuous_acked_seq = _max_continuous_acked_seqs[instance_id.lo];
    std::unordered_set<int64_t>::iterator it;
    while ((it = seqs.find(max_continuous_acked_seq + 1)) != seqs.end()) {
        seqs.erase(it);
        ++max_continuous_acked_seq;
    }
}

void SinkBuffer::_try_to_send_rpc(const TUniqueId& instance_id) {
    DeferOp decrease_defer([this]() { --_num_sending_rpc; });
    ++_num_sending_rpc;

    for (;;) {
        if (_is_finishing) {
            return;
        }

        auto& buffer = _buffers[instance_id.lo];

        bool too_much_brpc_process = false;
        if (_is_dest_merge) {
            // discontinuous_acked_window_size means that we are not received all the ack
            // with sequence from _max_continuous_acked_seqs[x] to _request_seqs[x]
            // Limit the size of the window to avoid buffering too much out-of-order data at the receiving side
            int64_t discontinuous_acked_window_size =
                    _request_seqs[instance_id.lo] - _max_continuous_acked_seqs[instance_id.lo];
            too_much_brpc_process = discontinuous_acked_window_size >= config::pipeline_sink_brpc_dop;
        } else {
            too_much_brpc_process = _num_in_flight_rpcs[instance_id.lo] >= config::pipeline_sink_brpc_dop;
        }
        if (buffer.empty() || too_much_brpc_process) {
            return;
        }

        TransmitChunkInfo request = buffer.front();
        bool need_wait = false;
        DeferOp pop_defer([&need_wait, &buffer]() {
            if (need_wait) {
                return;
            }
            buffer.pop();
        });

        // The order of data transmiting in IO level may not be strictly the same as
        // the order of submitting data packets
        // But we must guarantee that first packet must be received first
        if (_num_finished_rpcs[instance_id.lo] == 0 && _num_in_flight_rpcs[instance_id.lo] > 0) {
            need_wait = true;
            return;
        }
        if (request.params->eos()) {
            DeferOp eos_defer([this, &instance_id, &need_wait]() {
                if (need_wait) {
                    return;
                }
                if (--_num_remaining_eos == 0) {
                    _is_finishing = true;
                }
                --_num_sinkers[instance_id.lo];
            });
            // Only the last eos is sent to ExchangeSourceOperator. it must be guaranteed that
            // eos is the last packet to send to finish the input stream of the corresponding of
            // ExchangeSourceOperator and eos is sent exactly-once.
            if (_num_sinkers[instance_id.lo] > 1) {
                if (request.params->chunks_size() == 0) {
                    // Once the request is added to SinkBuffer, its ownership will also be transferred,
                    // so SinkBuffer needs to be responsible for the release of resources
                    request.params->release_finst_id();
                    continue;
                } else {
                    request.params->set_eos(false);
                }
            } else {
                // The order of data transmiting in IO level may not be strictly the same as
                // the order of submitting data packets
                // But we must guarantee that eos packent must be the last packet
                if (_num_in_flight_rpcs[instance_id.lo] > 0) {
                    need_wait = true;
                    return;
                }
            }
        }

        request.params->set_allocated_finst_id(&_instance_id2finst_id[instance_id.lo]);
        request.params->set_sequence(_request_seqs[instance_id.lo]++);

        auto* closure =
                new DisposableClosure<PTransmitChunkResult, ClosureContext>({instance_id, request.params->sequence()});
        closure->addFailedHandler([this](const ClosureContext& ctx) noexcept {
            _is_finishing = true;
            {
                std::lock_guard<std::mutex> l(*_mutexes[ctx.instance_id.lo]);
                ++_num_finished_rpcs[ctx.instance_id.lo];
                --_num_in_flight_rpcs[ctx.instance_id.lo];
            }
            --_total_in_flight_rpc;
            _fragment_ctx->cancel(Status::InternalError("transmit chunk rpc failed"));
            LOG(WARNING) << "transmit chunk rpc failed";
        });
        closure->addSuccessHandler([this](const ClosureContext& ctx, const PTransmitChunkResult& result,
                                          const int64_t send_timestamp) noexcept {
            Status status(result.status());
            {
                std::lock_guard<std::mutex> l(*_mutexes[ctx.instance_id.lo]);
                ++_num_finished_rpcs[ctx.instance_id.lo];
                --_num_in_flight_rpcs[ctx.instance_id.lo];
            }
            if (!status.ok()) {
                _is_finishing = true;
                _fragment_ctx->cancel(status);
                LOG(WARNING) << "transmit chunk rpc failed, " << status.message();
            } else {
                std::lock_guard<std::mutex> l(*_mutexes[ctx.instance_id.lo]);
                _process_send_window(ctx.instance_id, ctx.sequence);
                _update_network_time(ctx.instance_id, send_timestamp, result.receive_timestamp());
                _try_to_send_rpc(ctx.instance_id);
            }
            --_total_in_flight_rpc;
        });

        ++_total_in_flight_rpc;
        ++_num_in_flight_rpcs[instance_id.lo];

        closure->cntl.Reset();
        closure->cntl.set_timeout_ms(_brpc_timeout_ms);
        closure->cntl.request_attachment().append(request.attachment);
        request.brpc_stub->transmit_chunk(&closure->cntl, request.params.get(), &closure->result, closure);

        // Once the request is added to SinkBuffer, its ownership will also be transferred,
        // so SinkBuffer needs to be responsible for the release of resources
        request.params->release_finst_id();
        return;
    }
}
} // namespace starrocks::pipeline
