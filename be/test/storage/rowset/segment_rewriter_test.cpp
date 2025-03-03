// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Limited.

#include "storage/rowset/segment_rewriter.h"

#include <gtest/gtest.h>

#include <functional>
#include <iostream>

#include "column/datum_tuple.h"
#include "common/logging.h"
#include "env/env_memory.h"
#include "gen_cpp/olap_file.pb.h"
#include "gutil/strings/substitute.h"
#include "runtime/mem_pool.h"
#include "runtime/mem_tracker.h"
#include "storage/fs/file_block_manager.h"
#include "storage/olap_common.h"
#include "storage/rowset/column_iterator.h"
#include "storage/rowset/column_reader.h"
#include "storage/rowset/segment.h"
#include "storage/rowset/segment_writer.h"
#include "storage/rowset/vectorized/segment_options.h"
#include "storage/tablet_schema.h"
#include "storage/tablet_schema_helper.h"
#include "storage/vectorized/chunk_helper.h"
#include "storage/vectorized/chunk_iterator.h"
#include "testutil/assert.h"
#include "util/file_utils.h"

namespace starrocks {

using std::string;
using std::shared_ptr;

using std::vector;

class SegmentRewriterTest : public ::testing::Test {
protected:
    void SetUp() override {
        _env = Env::CreateSharedFromString("posix://").value();
        ASSIGN_OR_ABORT(_block_mgr, fs::fs_util::block_manager("posix://"));
        bool created;
        ASSERT_OK(_env->create_dir_if_missing(kSegmentDir, &created));

        _page_cache_mem_tracker = std::make_unique<MemTracker>();
        _tablet_meta_mem_tracker = std::make_unique<MemTracker>();
        StoragePageCache::create_global_cache(_page_cache_mem_tracker.get(), 1000000000);
    }

    void TearDown() override {
        ASSERT_TRUE(FileUtils::remove_all(kSegmentDir).ok());
        StoragePageCache::release_global_cache();
    }

    TabletSchema create_schema(const std::vector<TabletColumn>& columns, int num_short_key_columns = -1) {
        TabletSchema res;
        int num_key_columns = 0;
        for (auto& col : columns) {
            if (col.is_key()) {
                num_key_columns++;
            }
            res._cols.push_back(col);
        }
        res._num_key_columns = num_key_columns;
        res._num_short_key_columns = num_short_key_columns != -1 ? num_short_key_columns : num_key_columns;
        return res;
    }

    const std::string kSegmentDir = "./ut_dir/segment_rewriter_test";

    std::shared_ptr<Env> _env;
    std::shared_ptr<fs::BlockManager> _block_mgr;
    std::unique_ptr<MemTracker> _page_cache_mem_tracker;
    std::unique_ptr<MemTracker> _tablet_meta_mem_tracker;
};

TEST_F(SegmentRewriterTest, rewrite_test) {
    TabletSchema partial_tablet_schema = create_schema({create_int_key(1), create_int_key(2), create_int_value(4)});

    SegmentWriterOptions opts;
    opts.num_rows_per_block = 10;

    std::string file_name = kSegmentDir + "/partial_rowset";
    std::unique_ptr<fs::WritableBlock> wblock;
    fs::CreateBlockOptions wblock_opts({file_name});
    ASSERT_OK(_block_mgr->create_block(wblock_opts, &wblock));

    SegmentWriter writer(std::move(wblock), 0, &partial_tablet_schema, opts);
    ASSERT_OK(writer.init());

    int32_t chunk_size = config::vector_chunk_size;
    size_t num_rows = 10000;
    auto partial_schema = vectorized::ChunkHelper::convert_schema_to_format_v2(partial_tablet_schema);
    auto partial_chunk = vectorized::ChunkHelper::new_chunk(partial_schema, chunk_size);

    for (auto i = 0; i < num_rows % chunk_size; ++i) {
        partial_chunk->reset();
        auto& cols = partial_chunk->columns();
        for (auto j = 0; j < chunk_size; ++j) {
            if (i * chunk_size + j >= num_rows) {
                break;
            }
            cols[0]->append_datum(vectorized::Datum(static_cast<int32_t>(i * chunk_size + j)));
            cols[1]->append_datum(vectorized::Datum(static_cast<int32_t>(i * chunk_size + j + 1)));
            cols[2]->append_datum(vectorized::Datum(static_cast<int32_t>(i * chunk_size + j + 3)));
        }
        ASSERT_OK(writer.append_chunk(*partial_chunk));
    }

    uint64_t file_size = 0;
    uint64_t index_size;
    uint64_t footer_position;
    ASSERT_OK(writer.finalize(&file_size, &index_size, &footer_position));

    FooterPointerPB partial_rowset_footer;
    partial_rowset_footer.set_position(footer_position);
    partial_rowset_footer.set_size(file_size - footer_position);

    auto partial_segment =
            *Segment::open(_tablet_meta_mem_tracker.get(), _block_mgr, file_name, 0, &partial_tablet_schema);
    ASSERT_EQ(partial_segment->num_rows(), num_rows);

    TabletSchema tablet_schema = create_schema(
            {create_int_key(1), create_int_key(2), create_int_value(3), create_int_value(4), create_int_value(5)});
    std::string dst_file_name = kSegmentDir + "/rewrite_rowset";
    std::vector<uint32_t> read_column_ids{2, 4};
    std::vector<std::unique_ptr<vectorized::Column>> write_columns(read_column_ids.size());
    for (auto i = 0; i < read_column_ids.size(); ++i) {
        const auto read_column_id = read_column_ids[i];
        auto tablet_column = tablet_schema.column(read_column_id);
        auto column =
                vectorized::ChunkHelper::column_from_field_type(tablet_column.type(), tablet_column.is_nullable());
        write_columns[i] = column->clone_empty();
        for (auto j = 0; j < num_rows; ++j) {
            write_columns[i]->append_datum(vectorized::Datum(static_cast<int32_t>(j + read_column_ids[i])));
        }
    }

    ASSERT_OK(SegmentRewriter::rewrite(file_name, dst_file_name, tablet_schema, read_column_ids, write_columns,
                                       partial_segment->id(), partial_rowset_footer));

    auto segment = *Segment::open(_tablet_meta_mem_tracker.get(), _block_mgr, dst_file_name, 0, &tablet_schema);
    ASSERT_EQ(segment->num_rows(), num_rows);

    vectorized::SegmentReadOptions seg_options;
    seg_options.block_mgr = _block_mgr;
    OlapReaderStatistics stats;
    seg_options.stats = &stats;
    auto schema = vectorized::ChunkHelper::convert_schema_to_format_v2(tablet_schema);
    auto res = segment->new_iterator(schema, seg_options);
    ASSERT_FALSE(res.status().is_end_of_file() || !res.ok() || res.value() == nullptr);
    auto seg_iterator = res.value();

    size_t count = 0;
    auto chunk = vectorized::ChunkHelper::new_chunk(schema, chunk_size);
    while (true) {
        chunk->reset();
        auto st = seg_iterator->get_next(chunk.get());
        if (st.is_end_of_file()) {
            break;
        }
        ASSERT_FALSE(!st.ok());
        for (auto i = 0; i < chunk->num_rows(); ++i) {
            EXPECT_EQ(count, chunk->get(i)[0].get_int32());
            EXPECT_EQ(count + 1, chunk->get(i)[1].get_int32());
            EXPECT_EQ(count + 2, chunk->get(i)[2].get_int32());
            EXPECT_EQ(count + 3, chunk->get(i)[3].get_int32());
            EXPECT_EQ(count + 4, chunk->get(i)[4].get_int32());
            ++count;
        }
    }
    EXPECT_EQ(count, num_rows);

    // add useless string to partial segment
    std::unique_ptr<fs::WritableBlock> wblock_tmp;
    fs::CreateBlockOptions wblock_opts_tmp({file_name});
    wblock_opts_tmp.mode = Env::MUST_EXIST;
    ASSERT_OK(_block_mgr->create_block(wblock_opts_tmp, &wblock_tmp));
    for (int i = 0; i < 10; i++) {
        wblock_tmp->append("test");
    }

    std::vector<std::unique_ptr<vectorized::Column>> new_write_columns(read_column_ids.size());
    for (auto i = 0; i < read_column_ids.size(); ++i) {
        const auto read_column_id = read_column_ids[i];
        auto tablet_column = tablet_schema.column(read_column_id);
        auto column =
                vectorized::ChunkHelper::column_from_field_type(tablet_column.type(), tablet_column.is_nullable());
        new_write_columns[i] = column->clone_empty();
        for (auto j = 0; j < num_rows; ++j) {
            new_write_columns[i]->append_datum(vectorized::Datum(static_cast<int32_t>(j + read_column_ids[i])));
        }
    }
    ASSERT_OK(SegmentRewriter::rewrite(file_name, tablet_schema, read_column_ids, new_write_columns,
                                       partial_segment->id(), partial_rowset_footer));
    auto rewrite_segment = *Segment::open(_tablet_meta_mem_tracker.get(), _block_mgr, file_name, 0, &tablet_schema);

    ASSERT_EQ(rewrite_segment->num_rows(), num_rows);
    res = rewrite_segment->new_iterator(schema, seg_options);
    ASSERT_FALSE(res.status().is_end_of_file() || !res.ok() || res.value() == nullptr);
    auto rewrite_seg_iterator = res.value();

    count = 0;
    chunk = vectorized::ChunkHelper::new_chunk(schema, chunk_size);
    while (true) {
        chunk->reset();
        auto st = rewrite_seg_iterator->get_next(chunk.get());
        if (st.is_end_of_file()) {
            break;
        }
        ASSERT_FALSE(!st.ok());
        for (auto i = 0; i < chunk->num_rows(); ++i) {
            EXPECT_EQ(count, chunk->get(i)[0].get_int32());
            EXPECT_EQ(count + 1, chunk->get(i)[1].get_int32());
            EXPECT_EQ(count + 2, chunk->get(i)[2].get_int32());
            EXPECT_EQ(count + 3, chunk->get(i)[3].get_int32());
            EXPECT_EQ(count + 4, chunk->get(i)[4].get_int32());
            ++count;
        }
    }
    EXPECT_EQ(count, num_rows);
}

} // namespace starrocks