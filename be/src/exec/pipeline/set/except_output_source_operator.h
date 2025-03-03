// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Limited.

#pragma once

#include "exec/pipeline/operator.h"
#include "exec/pipeline/set/except_context.h"
#include "exec/pipeline/source_operator.h"

namespace starrocks::pipeline {

// ExceptOutputSourceOperator traverses the hast set and picks up undeleted entries after probe phase is finished.
// For more detail information, see the comments of class ExceptBuildSinkOperator.
class ExceptOutputSourceOperator final : public SourceOperator {
public:
    ExceptOutputSourceOperator(OperatorFactory* factory, int32_t id, int32_t plan_node_id,
                               std::shared_ptr<ExceptContext> except_ctx, const int32_t dependency_index)
            : SourceOperator(factory, id, "except_output_source", plan_node_id),
              _except_ctx(std::move(except_ctx)),
              _dependency_index(dependency_index) {
        _except_ctx->ref();
    }

    bool has_output() const override {
        return _except_ctx->is_dependency_finished(_dependency_index) && !_except_ctx->is_output_finished();
    }

    bool is_finished() const override {
        return _except_ctx->is_dependency_finished(_dependency_index) && _except_ctx->is_output_finished();
    }

    Status set_finished(RuntimeState* state) override { return _except_ctx->set_finished(); }

    StatusOr<vectorized::ChunkPtr> pull_chunk(RuntimeState* state) override;

    void close(RuntimeState* state) override;

private:
    std::shared_ptr<ExceptContext> _except_ctx;
    const int32_t _dependency_index;
};

class ExceptOutputSourceOperatorFactory final : public SourceOperatorFactory {
public:
    ExceptOutputSourceOperatorFactory(int32_t id, int32_t plan_node_id,
                                      ExceptPartitionContextFactoryPtr except_partition_ctx_factory,
                                      const int32_t dependency_index)
            : SourceOperatorFactory(id, "except_output_source", plan_node_id),
              _except_partition_ctx_factory(std::move(except_partition_ctx_factory)),
              _dependency_index(dependency_index) {}

    OperatorPtr create(int32_t degree_of_parallelism, int32_t driver_sequence) override {
        return std::make_shared<ExceptOutputSourceOperator>(
                this, _id, _plan_node_id, _except_partition_ctx_factory->get_or_create(driver_sequence),
                _dependency_index);
    }

    void close(RuntimeState* state) override;

private:
    ExceptPartitionContextFactoryPtr _except_partition_ctx_factory;
    const int32_t _dependency_index;
};

} // namespace starrocks::pipeline
