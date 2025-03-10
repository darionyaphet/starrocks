// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Limited.
package com.starrocks.sql.plan;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.starrocks.analysis.AggregateInfo;
import com.starrocks.analysis.AssertNumRowsElement;
import com.starrocks.analysis.Expr;
import com.starrocks.analysis.FunctionCallExpr;
import com.starrocks.analysis.JoinOperator;
import com.starrocks.analysis.OrderByElement;
import com.starrocks.analysis.SlotDescriptor;
import com.starrocks.analysis.SlotId;
import com.starrocks.analysis.SlotRef;
import com.starrocks.analysis.SortInfo;
import com.starrocks.analysis.TupleDescriptor;
import com.starrocks.analysis.TupleId;
import com.starrocks.catalog.Catalog;
import com.starrocks.catalog.ColocateTableIndex;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.FunctionSet;
import com.starrocks.catalog.JDBCTable;
import com.starrocks.catalog.MaterializedIndex;
import com.starrocks.catalog.MysqlTable;
import com.starrocks.catalog.OlapTable;
import com.starrocks.catalog.Partition;
import com.starrocks.catalog.Table;
import com.starrocks.catalog.Tablet;
import com.starrocks.catalog.Type;
import com.starrocks.common.Config;
import com.starrocks.common.IdGenerator;
import com.starrocks.common.UserException;
import com.starrocks.planner.AggregationNode;
import com.starrocks.planner.AnalyticEvalNode;
import com.starrocks.planner.AssertNumRowsNode;
import com.starrocks.planner.CrossJoinNode;
import com.starrocks.planner.DataPartition;
import com.starrocks.planner.DecodeNode;
import com.starrocks.planner.EmptySetNode;
import com.starrocks.planner.EsScanNode;
import com.starrocks.planner.ExceptNode;
import com.starrocks.planner.ExchangeNode;
import com.starrocks.planner.HashJoinNode;
import com.starrocks.planner.HdfsScanNode;
import com.starrocks.planner.HudiScanNode;
import com.starrocks.planner.IcebergScanNode;
import com.starrocks.planner.IntersectNode;
import com.starrocks.planner.JDBCScanNode;
import com.starrocks.planner.MetaScanNode;
import com.starrocks.planner.MultiCastPlanFragment;
import com.starrocks.planner.MysqlScanNode;
import com.starrocks.planner.OlapScanNode;
import com.starrocks.planner.PlanFragment;
import com.starrocks.planner.PlanNode;
import com.starrocks.planner.ProjectNode;
import com.starrocks.planner.RepeatNode;
import com.starrocks.planner.RuntimeFilterId;
import com.starrocks.planner.SchemaScanNode;
import com.starrocks.planner.SelectNode;
import com.starrocks.planner.SetOperationNode;
import com.starrocks.planner.SortNode;
import com.starrocks.planner.TableFunctionNode;
import com.starrocks.planner.UnionNode;
import com.starrocks.qe.ConnectContext;
import com.starrocks.service.FrontendOptions;
import com.starrocks.sql.common.StarRocksPlannerException;
import com.starrocks.sql.optimizer.OptExpression;
import com.starrocks.sql.optimizer.OptExpressionVisitor;
import com.starrocks.sql.optimizer.Utils;
import com.starrocks.sql.optimizer.base.ColumnRefFactory;
import com.starrocks.sql.optimizer.base.ColumnRefSet;
import com.starrocks.sql.optimizer.base.DistributionSpec;
import com.starrocks.sql.optimizer.base.GatherDistributionSpec;
import com.starrocks.sql.optimizer.base.HashDistributionDesc;
import com.starrocks.sql.optimizer.base.HashDistributionSpec;
import com.starrocks.sql.optimizer.base.OrderSpec;
import com.starrocks.sql.optimizer.base.Ordering;
import com.starrocks.sql.optimizer.operator.Operator;
import com.starrocks.sql.optimizer.operator.OperatorType;
import com.starrocks.sql.optimizer.operator.Projection;
import com.starrocks.sql.optimizer.operator.ScanOperatorPredicates;
import com.starrocks.sql.optimizer.operator.physical.PhysicalAssertOneRowOperator;
import com.starrocks.sql.optimizer.operator.physical.PhysicalCTEConsumeOperator;
import com.starrocks.sql.optimizer.operator.physical.PhysicalCTEProduceOperator;
import com.starrocks.sql.optimizer.operator.physical.PhysicalDecodeOperator;
import com.starrocks.sql.optimizer.operator.physical.PhysicalDistributionOperator;
import com.starrocks.sql.optimizer.operator.physical.PhysicalEsScanOperator;
import com.starrocks.sql.optimizer.operator.physical.PhysicalFilterOperator;
import com.starrocks.sql.optimizer.operator.physical.PhysicalHashAggregateOperator;
import com.starrocks.sql.optimizer.operator.physical.PhysicalHashJoinOperator;
import com.starrocks.sql.optimizer.operator.physical.PhysicalHiveScanOperator;
import com.starrocks.sql.optimizer.operator.physical.PhysicalHudiScanOperator;
import com.starrocks.sql.optimizer.operator.physical.PhysicalIcebergScanOperator;
import com.starrocks.sql.optimizer.operator.physical.PhysicalJDBCScanOperator;
import com.starrocks.sql.optimizer.operator.physical.PhysicalMetaScanOperator;
import com.starrocks.sql.optimizer.operator.physical.PhysicalMysqlScanOperator;
import com.starrocks.sql.optimizer.operator.physical.PhysicalOlapScanOperator;
import com.starrocks.sql.optimizer.operator.physical.PhysicalProjectOperator;
import com.starrocks.sql.optimizer.operator.physical.PhysicalRepeatOperator;
import com.starrocks.sql.optimizer.operator.physical.PhysicalScanOperator;
import com.starrocks.sql.optimizer.operator.physical.PhysicalSchemaScanOperator;
import com.starrocks.sql.optimizer.operator.physical.PhysicalSetOperation;
import com.starrocks.sql.optimizer.operator.physical.PhysicalTableFunctionOperator;
import com.starrocks.sql.optimizer.operator.physical.PhysicalTopNOperator;
import com.starrocks.sql.optimizer.operator.physical.PhysicalUnionOperator;
import com.starrocks.sql.optimizer.operator.physical.PhysicalValuesOperator;
import com.starrocks.sql.optimizer.operator.physical.PhysicalWindowOperator;
import com.starrocks.sql.optimizer.operator.scalar.BinaryPredicateOperator;
import com.starrocks.sql.optimizer.operator.scalar.CallOperator;
import com.starrocks.sql.optimizer.operator.scalar.ColumnRefOperator;
import com.starrocks.sql.optimizer.operator.scalar.ScalarOperator;
import com.starrocks.sql.optimizer.rewrite.AddDecodeNodeForDictStringRule.DecodeVisitor;
import com.starrocks.sql.optimizer.rule.transformation.JoinPredicateUtils;
import com.starrocks.sql.optimizer.statistics.Statistics;
import com.starrocks.thrift.TPartitionType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.starrocks.catalog.Function.CompareMode.IS_NONSTRICT_SUPERTYPE_OF;
import static com.starrocks.sql.common.ErrorType.INTERNAL_ERROR;
import static com.starrocks.sql.common.UnsupportedException.unsupportedException;
import static com.starrocks.sql.optimizer.rule.transformation.JoinPredicateUtils.getEqConj;

/**
 * PlanFragmentBuilder used to transform physical operator to exec plan fragment
 */
public class PlanFragmentBuilder {

    private static final Logger LOG = LogManager.getLogger(PlanFragmentBuilder.class);

    public ExecPlan createPhysicalPlan(OptExpression plan, ConnectContext connectContext,
                                       List<ColumnRefOperator> outputColumns, ColumnRefFactory columnRefFactory,
                                       List<String> colNames) {
        ExecPlan execPlan = new ExecPlan(connectContext, colNames, plan, outputColumns);
        createOutputFragment(new PhysicalPlanTranslator(columnRefFactory).visit(plan, execPlan), execPlan,
                outputColumns);
        execPlan.setPlanCount(plan.getPlanCount());
        return finalizeFragments(execPlan);
    }

    public ExecPlan createPhysicalPlanWithoutOutputFragment(OptExpression plan,
                                                            ConnectContext connectContext,
                                                            List<ColumnRefOperator> outputColumns,
                                                            ColumnRefFactory columnRefFactory,
                                                            List<String> colNames) {
        ExecPlan execPlan = new ExecPlan(connectContext, colNames, plan, outputColumns);
        PlanFragment root = new PhysicalPlanTranslator(columnRefFactory).visit(plan, execPlan);

        List<Expr> outputExprs = outputColumns.stream().map(variable -> ScalarOperatorToExpr
                .buildExecExpression(variable,
                        new ScalarOperatorToExpr.FormatterContext(execPlan.getColRefToExpr()))
        ).collect(Collectors.toList());
        root.setOutputExprs(outputExprs);
        execPlan.getOutputExprs().addAll(outputExprs);

        execPlan.setPlanCount(plan.getPlanCount());
        return finalizeFragments(execPlan);
    }

    public ExecPlan createStatisticPhysicalPlan(OptExpression plan,
                                                ConnectContext connectContext,
                                                List<ColumnRefOperator> outputColumns,
                                                ColumnRefFactory columnRefFactory, boolean isStatistic) {
        ExecPlan execPlan = new ExecPlan(connectContext, new ArrayList<>(), plan, outputColumns);
        createOutputFragment(new PhysicalPlanTranslator(columnRefFactory).visit(plan, execPlan), execPlan,
                outputColumns);

        List<PlanFragment> fragments = execPlan.getFragments();
        for (PlanFragment fragment : fragments) {
            fragment.finalizeForStatistic(isStatistic);
        }
        Collections.reverse(fragments);
        return execPlan;
    }

    private void createOutputFragment(PlanFragment inputFragment, ExecPlan execPlan,
                                      List<ColumnRefOperator> outputColumns) {
        if (inputFragment.getPlanRoot() instanceof ExchangeNode || !inputFragment.isPartitioned()) {
            List<Expr> outputExprs = outputColumns.stream().map(variable -> ScalarOperatorToExpr
                    .buildExecExpression(variable,
                            new ScalarOperatorToExpr.FormatterContext(execPlan.getColRefToExpr()))
            ).collect(Collectors.toList());
            inputFragment.setOutputExprs(outputExprs);
            execPlan.getOutputExprs().addAll(outputExprs);
            return;
        }

        List<Expr> outputExprs = outputColumns.stream().map(variable -> ScalarOperatorToExpr
                        .buildExecExpression(variable, new ScalarOperatorToExpr.FormatterContext(execPlan.getColRefToExpr())))
                .collect(Collectors.toList());
        execPlan.getOutputExprs().addAll(outputExprs);

        // Single tablet direct output
        // Note: If the fragment has right or full join and the join is local bucket shuffle join,
        // We shouldn't set result sink directly to top fragment, because we will hash multi reslt sink.
        if (!inputFragment.hashLocalBucketShuffleRightOrFullJoin(inputFragment.getPlanRoot())
                && execPlan.getScanNodes().stream().allMatch(d -> d instanceof OlapScanNode)
                && execPlan.getScanNodes().stream().map(d -> ((OlapScanNode) d).getScanTabletIds().size())
                .reduce(Integer::sum).orElse(2) <= 1) {
            inputFragment.setOutputExprs(outputExprs);
            return;
        }

        ExchangeNode exchangeNode =
                new ExchangeNode(execPlan.getNextNodeId(), inputFragment.getPlanRoot(), false);
        exchangeNode.setNumInstances(1);
        PlanFragment exchangeFragment =
                new PlanFragment(execPlan.getNextFragmentId(), exchangeNode, DataPartition.UNPARTITIONED);
        inputFragment.setDestination(exchangeNode);
        inputFragment.setOutputPartition(DataPartition.UNPARTITIONED);

        exchangeFragment.setOutputExprs(outputExprs);
        execPlan.getFragments().add(exchangeFragment);
    }

    private ExecPlan finalizeFragments(ExecPlan execPlan) {
        try {
            List<PlanFragment> fragments = execPlan.getFragments();
            for (PlanFragment fragment : fragments) {
                fragment.finalize(null, false);
            }
            Collections.reverse(fragments);
            // compute local_rf_waiting_set for each PlanNode.
            // when enable_pipeline_engine=true and enable_global_runtime_filter=false, we should clear
            // runtime filters from PlanNode.
            boolean shouldClearRuntimeFilters = ConnectContext.get() != null &&
                    !ConnectContext.get().getSessionVariable().getEnableGlobalRuntimeFilter() &&
                    ConnectContext.get().getSessionVariable().isEnablePipelineEngine();
            for (PlanFragment fragment : fragments) {
                fragment.computeLocalRfWaitingSet(fragment.getPlanRoot(), shouldClearRuntimeFilters);
            }
        } catch (UserException e) {
            throw new StarRocksPlannerException("Create fragment fail, " + e.getMessage(), INTERNAL_ERROR);
        }

        return execPlan;
    }

    private static class PhysicalPlanTranslator extends OptExpressionVisitor<PlanFragment, ExecPlan> {
        private final ColumnRefFactory columnRefFactory;
        private final IdGenerator<RuntimeFilterId> runtimeFilterIdIdGenerator = RuntimeFilterId.createGenerator();

        public PhysicalPlanTranslator(ColumnRefFactory columnRefFactory) {
            this.columnRefFactory = columnRefFactory;
        }

        public PlanFragment visit(OptExpression optExpression, ExecPlan context) {
            PlanFragment fragment = optExpression.getOp().accept(this, optExpression, context);
            Projection projection = (optExpression.getOp()).getProjection();

            if (projection == null) {
                return fragment;
            } else {
                return buildProjectNode(optExpression, projection, fragment, context);
            }
        }

        private void setUnUsedOutputColumns(PhysicalOlapScanOperator node, OlapScanNode scanNode,
                                            List<ScalarOperator> predicates) {
            if (ConnectContext.get().getSessionVariable().isAbleFilterUnusedColumnsInScanStage()) {
                List<ColumnRefOperator> outputColumns = node.getOutputColumns();
                Set<Integer> outputColumnIds = new HashSet<Integer>();
                for (ColumnRefOperator colref : outputColumns) {
                    outputColumnIds.add(colref.getId());
                }

                // we only support single pred like: a = xx, single pre can push down to scan node
                // complex pred like: a + b = xx, can not push down to scan node yet
                // so the columns in complex pred, it useful for the stage after scan
                Set<Integer> singlePredColumnIds = new HashSet<Integer>();
                Set<Integer> complexPredColumnIds = new HashSet<Integer>();
                for (ScalarOperator predicate : predicates) {
                    ColumnRefSet usedColumns = predicate.getUsedColumns();
                    if (DecodeVisitor.isSimpleStrictPredicate(predicate)) {
                        for (int cid : usedColumns.getColumnIds()) {
                            singlePredColumnIds.add(cid);
                        }
                    } else {
                        for (int cid : usedColumns.getColumnIds()) {
                            complexPredColumnIds.add(cid);
                        }
                    }
                }

                Set<Integer> unUsedOutputColumnIds = new HashSet<Integer>();
                Map<Integer, Integer> dictStringIdToIntIds = node.getDictStringIdToIntIds();
                for (Integer cid : singlePredColumnIds) {
                    Integer newCid = cid;
                    if (dictStringIdToIntIds.containsKey(cid)) {
                        newCid = dictStringIdToIntIds.get(cid);
                    }
                    if (!complexPredColumnIds.contains(newCid) && !outputColumnIds.contains(newCid)) {
                        unUsedOutputColumnIds.add(newCid);
                    }
                }
                scanNode.setUnUsedOutputStringColumns(unUsedOutputColumnIds);
            }
        }

        @Override
        public PlanFragment visitPhysicalProject(OptExpression optExpr, ExecPlan context) {
            PhysicalProjectOperator node = (PhysicalProjectOperator) optExpr.getOp();
            PlanFragment inputFragment = visit(optExpr.inputAt(0), context);

            Preconditions.checkState(!node.getColumnRefMap().isEmpty());

            TupleDescriptor tupleDescriptor = context.getDescTbl().createTupleDescriptor();

            Map<SlotId, Expr> commonSubOperatorMap = Maps.newHashMap();
            for (Map.Entry<ColumnRefOperator, ScalarOperator> entry : node.getCommonSubOperatorMap().entrySet()) {
                Expr expr = ScalarOperatorToExpr.buildExecExpression(entry.getValue(),
                        new ScalarOperatorToExpr.FormatterContext(context.getColRefToExpr(),
                                node.getCommonSubOperatorMap()));

                commonSubOperatorMap.put(new SlotId(entry.getKey().getId()), expr);

                SlotDescriptor slotDescriptor =
                        context.getDescTbl().addSlotDescriptor(tupleDescriptor, new SlotId(entry.getKey().getId()));
                slotDescriptor.setIsNullable(expr.isNullable());
                slotDescriptor.setIsMaterialized(false);
                slotDescriptor.setType(expr.getType());
                context.getColRefToExpr().put(entry.getKey(), new SlotRef(entry.getKey().toString(), slotDescriptor));
            }

            Map<SlotId, Expr> projectMap = Maps.newHashMap();
            for (Map.Entry<ColumnRefOperator, ScalarOperator> entry : node.getColumnRefMap().entrySet()) {
                Expr expr = ScalarOperatorToExpr.buildExecExpression(entry.getValue(),
                        new ScalarOperatorToExpr.FormatterContext(context.getColRefToExpr(), node.getColumnRefMap()));

                projectMap.put(new SlotId(entry.getKey().getId()), expr);

                SlotDescriptor slotDescriptor =
                        context.getDescTbl().addSlotDescriptor(tupleDescriptor, new SlotId(entry.getKey().getId()));
                slotDescriptor.setIsNullable(expr.isNullable());
                slotDescriptor.setIsMaterialized(true);
                slotDescriptor.setType(expr.getType());

                context.getColRefToExpr().put(entry.getKey(), new SlotRef(entry.getKey().toString(), slotDescriptor));
            }

            ProjectNode projectNode =
                    new ProjectNode(context.getNextNodeId(),
                            tupleDescriptor,
                            inputFragment.getPlanRoot(),
                            projectMap,
                            commonSubOperatorMap);

            projectNode.setHasNullableGenerateChild();
            projectNode.computeStatistics(optExpr.getStatistics());

            for (SlotId sid : projectMap.keySet()) {
                SlotDescriptor slotDescriptor = tupleDescriptor.getSlot(sid.asInt());
                slotDescriptor.setIsNullable(slotDescriptor.getIsNullable() | projectNode.isHasNullableGenerateChild());
            }
            tupleDescriptor.computeMemLayout();

            projectNode.setLimit(inputFragment.getPlanRoot().getLimit());
            inputFragment.setPlanRoot(projectNode);
            return inputFragment;
        }

        public PlanFragment buildProjectNode(OptExpression optExpression, Projection node, PlanFragment inputFragment,
                                             ExecPlan context) {
            if (node == null) {
                return inputFragment;
            }

            Preconditions.checkState(!node.getColumnRefMap().isEmpty());

            TupleDescriptor tupleDescriptor = context.getDescTbl().createTupleDescriptor();

            Map<SlotId, Expr> commonSubOperatorMap = Maps.newHashMap();
            for (Map.Entry<ColumnRefOperator, ScalarOperator> entry : node.getCommonSubOperatorMap().entrySet()) {
                Expr expr = ScalarOperatorToExpr.buildExecExpression(entry.getValue(),
                        new ScalarOperatorToExpr.FormatterContext(context.getColRefToExpr(),
                                node.getCommonSubOperatorMap()));

                commonSubOperatorMap.put(new SlotId(entry.getKey().getId()), expr);

                SlotDescriptor slotDescriptor =
                        context.getDescTbl().addSlotDescriptor(tupleDescriptor, new SlotId(entry.getKey().getId()));
                slotDescriptor.setIsNullable(entry.getValue().isNullable());
                slotDescriptor.setIsMaterialized(false);
                slotDescriptor.setType(expr.getType());
                context.getColRefToExpr().put(entry.getKey(), new SlotRef(entry.getKey().toString(), slotDescriptor));
            }

            Map<SlotId, Expr> projectMap = Maps.newHashMap();
            for (Map.Entry<ColumnRefOperator, ScalarOperator> entry : node.getColumnRefMap().entrySet()) {
                Expr expr = ScalarOperatorToExpr.buildExecExpression(entry.getValue(),
                        new ScalarOperatorToExpr.FormatterContext(context.getColRefToExpr(), node.getColumnRefMap()));

                projectMap.put(new SlotId(entry.getKey().getId()), expr);

                SlotDescriptor slotDescriptor =
                        context.getDescTbl().addSlotDescriptor(tupleDescriptor, new SlotId(entry.getKey().getId()));
                slotDescriptor.setIsNullable(entry.getValue().isNullable());
                slotDescriptor.setIsMaterialized(true);
                slotDescriptor.setType(expr.getType());

                context.getColRefToExpr().put(entry.getKey(), new SlotRef(entry.getKey().toString(), slotDescriptor));
            }

            ProjectNode projectNode =
                    new ProjectNode(context.getNextNodeId(),
                            tupleDescriptor,
                            inputFragment.getPlanRoot(),
                            projectMap,
                            commonSubOperatorMap);

            projectNode.setHasNullableGenerateChild();

            Statistics statistics = optExpression.getStatistics();
            Statistics.Builder b = Statistics.builder();
            b.setOutputRowCount(statistics.getOutputRowCount());
            b.addColumnStatistics(statistics.getOutputColumnsStatistics(new ColumnRefSet(node.getOutputColumns())));
            projectNode.computeStatistics(b.build());

            for (SlotId sid : projectMap.keySet()) {
                SlotDescriptor slotDescriptor = tupleDescriptor.getSlot(sid.asInt());
                slotDescriptor.setIsNullable(slotDescriptor.getIsNullable() | projectNode.isHasNullableGenerateChild());
            }
            tupleDescriptor.computeMemLayout();

            projectNode.setLimit(inputFragment.getPlanRoot().getLimit());
            inputFragment.setPlanRoot(projectNode);
            return inputFragment;
        }

        @Override
        public PlanFragment visitPhysicalDecode(OptExpression optExpression, ExecPlan context) {
            PhysicalDecodeOperator node = (PhysicalDecodeOperator) optExpression.getOp();
            PlanFragment inputFragment = visit(optExpression.inputAt(0), context);

            TupleDescriptor tupleDescriptor = context.getDescTbl().createTupleDescriptor();

            for (TupleId tupleId : inputFragment.getPlanRoot().getTupleIds()) {
                TupleDescriptor childTuple = context.getDescTbl().getTupleDesc(tupleId);
                ArrayList<SlotDescriptor> slots = childTuple.getSlots();
                for (SlotDescriptor slot : slots) {
                    int slotId = slot.getId().asInt();
                    boolean isNullable = slot.getIsNullable();
                    if (node.getDictToStrings().containsKey(slotId)) {
                        Integer stringSlotId = node.getDictToStrings().get(slotId);
                        SlotDescriptor slotDescriptor =
                                context.getDescTbl().addSlotDescriptor(tupleDescriptor, new SlotId(stringSlotId));
                        slotDescriptor.setIsNullable(isNullable);
                        slotDescriptor.setIsMaterialized(true);
                        slotDescriptor.setType(Type.VARCHAR);

                        context.getColRefToExpr().put(new ColumnRefOperator(stringSlotId, Type.VARCHAR,
                                        "<dict-code>", slotDescriptor.getIsNullable()),
                                new SlotRef(stringSlotId.toString(), slotDescriptor));
                    } else {
                        // Note: must change the parent tuple id
                        SlotDescriptor slotDescriptor = new SlotDescriptor(slot.getId(), tupleDescriptor, slot);
                        tupleDescriptor.addSlot(slotDescriptor);
                    }
                }
            }

            Map<SlotId, Expr> projectMap = Maps.newHashMap();
            for (Map.Entry<ColumnRefOperator, ScalarOperator> entry : node.getStringFunctions().entrySet()) {
                Expr expr = ScalarOperatorToExpr.buildExecExpression(entry.getValue(),
                        new ScalarOperatorToExpr.FormatterContext(context.getColRefToExpr(),
                                node.getStringFunctions()));

                projectMap.put(new SlotId(entry.getKey().getId()), expr);
                Preconditions.checkState(context.getColRefToExpr().containsKey(entry.getKey()));
            }

            tupleDescriptor.computeMemLayout();

            DecodeNode decodeNode = new DecodeNode(context.getNextNodeId(),
                    tupleDescriptor,
                    inputFragment.getPlanRoot(),
                    node.getDictToStrings(), projectMap);
            decodeNode.computeStatistics(optExpression.getStatistics());
            decodeNode.setLimit(node.getLimit());

            inputFragment.setPlanRoot(decodeNode);
            return inputFragment;
        }

        @Override
        public PlanFragment visitPhysicalOlapScan(OptExpression optExpr, ExecPlan context) {
            PhysicalOlapScanOperator node = (PhysicalOlapScanOperator) optExpr.getOp();

            OlapTable referenceTable = (OlapTable) node.getTable();
            context.getDescTbl().addReferencedTable(referenceTable);
            TupleDescriptor tupleDescriptor = context.getDescTbl().createTupleDescriptor();
            tupleDescriptor.setTable(referenceTable);

            OlapScanNode scanNode = new OlapScanNode(context.getNextNodeId(), tupleDescriptor, "OlapScanNode");
            scanNode.setLimit(node.getLimit());
            scanNode.computeStatistics(optExpr.getStatistics());

            // set tablet
            try {
                scanNode.updateScanInfo(node.getSelectedPartitionId(),
                        node.getSelectedTabletId(),
                        node.getSelectedIndexId());
                long selectedIndexId = node.getSelectedIndexId();
                long totalTabletsNum = 0;
                // Compatible with old tablet selected, copy from "OlapScanNode::computeTabletInfo"
                // we can remove code when refactor tablet select
                for (Long partitionId : node.getSelectedPartitionId()) {
                    final Partition partition = referenceTable.getPartition(partitionId);
                    final MaterializedIndex selectedTable = partition.getIndex(selectedIndexId);

                    final List<Tablet> tablets = Lists.newArrayList();
                    for (Long id : node.getSelectedTabletId()) {
                        if (selectedTable.getTablet(id) != null) {
                            tablets.add(selectedTable.getTablet(id));
                        }
                    }

                    long localBeId = -1;
                    if (Config.enable_local_replica_selection) {
                        localBeId = Catalog.getCurrentSystemInfo()
                                .getBackendIdByHost(FrontendOptions.getLocalHostAddress());
                    }

                    List<Long> allTabletIds = selectedTable.getTabletIdsInOrder();
                    Map<Long, Integer> tabletId2BucketSeq = Maps.newHashMap();
                    for (int i = 0; i < allTabletIds.size(); i++) {
                        tabletId2BucketSeq.put(allTabletIds.get(i), i);
                    }

                    totalTabletsNum += selectedTable.getTablets().size();
                    scanNode.setTabletId2BucketSeq(tabletId2BucketSeq);
                    scanNode.addScanRangeLocations(partition, selectedTable, tablets, localBeId);
                }
                scanNode.setTotalTabletsNum(totalTabletsNum);
            } catch (UserException e) {
                throw new StarRocksPlannerException(
                        "Build Exec OlapScanNode fail, scan info is invalid," + e.getMessage(),
                        INTERNAL_ERROR);
            }

            // set slot
            for (Map.Entry<ColumnRefOperator, Column> entry : node.getColRefToColumnMetaMap().entrySet()) {
                SlotDescriptor slotDescriptor =
                        context.getDescTbl().addSlotDescriptor(tupleDescriptor, new SlotId(entry.getKey().getId()));
                slotDescriptor.setColumn(entry.getValue());
                slotDescriptor.setIsNullable(entry.getValue().isAllowNull());
                slotDescriptor.setIsMaterialized(true);
                context.getColRefToExpr().put(entry.getKey(), new SlotRef(entry.getKey().toString(), slotDescriptor));
            }

            for (ColumnRefOperator entry : node.getGlobalDictStringColumns()) {
                SlotDescriptor slotDescriptor =
                        context.getDescTbl().addSlotDescriptor(tupleDescriptor, new SlotId(entry.getId()));
                slotDescriptor.setIsNullable(entry.isNullable());
                slotDescriptor.setType(entry.getType());
                slotDescriptor.setIsMaterialized(false);
                context.getColRefToExpr().put(entry, new SlotRef(entry.toString(), slotDescriptor));
            }

            // set predicate
            List<ScalarOperator> predicates = Utils.extractConjuncts(node.getPredicate());
            ScalarOperatorToExpr.FormatterContext formatterContext =
                    new ScalarOperatorToExpr.FormatterContext(context.getColRefToExpr());

            for (ScalarOperator predicate : predicates) {
                scanNode.getConjuncts().add(ScalarOperatorToExpr.buildExecExpression(predicate, formatterContext));
            }

            tupleDescriptor.computeMemLayout();

            // set unused output columns
            setUnUsedOutputColumns(node, scanNode, predicates);

            // set isPreAggregation
            scanNode.setIsPreAggregation(node.isPreAggregation(), node.getTurnOffReason());
            scanNode.setDictStringIdToIntIds(node.getDictStringIdToIntIds());
            scanNode.updateAppliedDictStringColumns(node.getGlobalDicts().stream().
                    map(entry -> entry.first).collect(Collectors.toSet()));

            context.getScanNodes().add(scanNode);
            PlanFragment fragment =
                    new PlanFragment(context.getNextFragmentId(), scanNode, DataPartition.RANDOM);
            fragment.setQueryGlobalDicts(node.getGlobalDicts());
            context.getFragments().add(fragment);
            return fragment;
        }

        @Override
        public PlanFragment visitPhysicalMetaScan(OptExpression optExpression, ExecPlan context) {
            PhysicalMetaScanOperator scan = (PhysicalMetaScanOperator) optExpression.getOp();

            context.getDescTbl().addReferencedTable(scan.getTable());
            TupleDescriptor tupleDescriptor = context.getDescTbl().createTupleDescriptor();
            tupleDescriptor.setTable(scan.getTable());

            MetaScanNode scanNode =
                    new MetaScanNode(context.getNextNodeId(),
                            tupleDescriptor, (OlapTable) scan.getTable(), scan.getAggColumnIdToNames());
            scanNode.computeRangeLocations();

            for (Map.Entry<ColumnRefOperator, Column> entry : scan.getColRefToColumnMetaMap().entrySet()) {
                SlotDescriptor slotDescriptor =
                        context.getDescTbl().addSlotDescriptor(tupleDescriptor, new SlotId(entry.getKey().getId()));
                slotDescriptor.setColumn(entry.getValue());
                slotDescriptor.setIsNullable(entry.getValue().isAllowNull());
                slotDescriptor.setIsMaterialized(true);
                context.getColRefToExpr().put(entry.getKey(), new SlotRef(entry.getKey().getName(), slotDescriptor));
            }
            tupleDescriptor.computeMemLayout();

            context.getScanNodes().add(scanNode);
            PlanFragment fragment =
                    new PlanFragment(context.getNextFragmentId(), scanNode, DataPartition.RANDOM);
            context.getFragments().add(fragment);
            return fragment;
        }

        private void prepareContextSlots(PhysicalScanOperator node, ExecPlan context, TupleDescriptor tupleDescriptor) {
            // set slot
            for (Map.Entry<ColumnRefOperator, Column> entry : node.getColRefToColumnMetaMap().entrySet()) {
                SlotDescriptor slotDescriptor =
                        context.getDescTbl().addSlotDescriptor(tupleDescriptor, new SlotId(entry.getKey().getId()));
                slotDescriptor.setColumn(entry.getValue());
                slotDescriptor.setIsNullable(entry.getValue().isAllowNull());
                slotDescriptor.setIsMaterialized(true);
                context.getColRefToExpr().put(entry.getKey(), new SlotRef(entry.getKey().toString(), slotDescriptor));
            }
        }

        private void prepareCommonExpr(HDFSScanNodePredicates scanNodePredicates,
                                       ScanOperatorPredicates predicates, ExecPlan context) {
            // set predicate
            List<ScalarOperator> noEvalPartitionConjuncts = predicates.getNoEvalPartitionConjuncts();
            List<ScalarOperator> nonPartitionConjuncts = predicates.getNonPartitionConjuncts();
            ScalarOperatorToExpr.FormatterContext formatterContext =
                    new ScalarOperatorToExpr.FormatterContext(context.getColRefToExpr());

            for (ScalarOperator noEvalPartitionConjunct : noEvalPartitionConjuncts) {
                scanNodePredicates.getNoEvalPartitionConjuncts().
                        add(ScalarOperatorToExpr.buildExecExpression(noEvalPartitionConjunct, formatterContext));
            }
            for (ScalarOperator nonPartitionConjunct : nonPartitionConjuncts) {
                scanNodePredicates.getNonPartitionConjuncts().
                        add(ScalarOperatorToExpr.buildExecExpression(nonPartitionConjunct, formatterContext));
            }
        }

        private void prepareMinMaxExpr(HDFSScanNodePredicates scanNodePredicates,
                                       ScanOperatorPredicates predicates, ExecPlan context) {
            /*
             * populates 'minMaxTuple' with slots for statistics values,
             * and populates 'minMaxConjuncts' with conjuncts pointing into the 'minMaxTuple'
             */
            List<ScalarOperator> minMaxConjuncts = predicates.getMinMaxConjuncts();
            TupleDescriptor minMaxTuple = context.getDescTbl().createTupleDescriptor();
            for (ScalarOperator minMaxConjunct : minMaxConjuncts) {
                for (ColumnRefOperator columnRefOperator : Utils.extractColumnRef(minMaxConjunct)) {
                    SlotDescriptor slotDescriptor =
                            context.getDescTbl()
                                    .addSlotDescriptor(minMaxTuple, new SlotId(columnRefOperator.getId()));
                    Column column = predicates.getMinMaxColumnRefMap().get(columnRefOperator);
                    slotDescriptor.setColumn(column);
                    slotDescriptor.setIsNullable(column.isAllowNull());
                    slotDescriptor.setIsMaterialized(true);
                    context.getColRefToExpr()
                            .put(columnRefOperator, new SlotRef(columnRefOperator.toString(), slotDescriptor));
                }
            }
            minMaxTuple.computeMemLayout();
            scanNodePredicates.setMinMaxTuple(minMaxTuple);
            ScalarOperatorToExpr.FormatterContext minMaxFormatterContext =
                    new ScalarOperatorToExpr.FormatterContext(context.getColRefToExpr());
            for (ScalarOperator minMaxConjunct : minMaxConjuncts) {
                scanNodePredicates.getMinMaxConjuncts().
                        add(ScalarOperatorToExpr.buildExecExpression(minMaxConjunct, minMaxFormatterContext));
            }
        }

        @Override
        public PlanFragment visitPhysicalHudiScan(OptExpression optExpression, ExecPlan context) {
            PhysicalHudiScanOperator node = (PhysicalHudiScanOperator) optExpression.getOp();
            ScanOperatorPredicates predicates = node.getScanOperatorPredicates();

            Table referenceTable = node.getTable();
            context.getDescTbl().addReferencedTable(referenceTable);
            TupleDescriptor tupleDescriptor = context.getDescTbl().createTupleDescriptor();
            tupleDescriptor.setTable(referenceTable);

            prepareContextSlots(node, context, tupleDescriptor);

            HudiScanNode hudiScanNode =
                    new HudiScanNode(context.getNextNodeId(), tupleDescriptor, "HudiScanNode");
            hudiScanNode.computeStatistics(optExpression.getStatistics());
            try {
                HDFSScanNodePredicates scanNodePredicates = hudiScanNode.getPredictsExpr();
                scanNodePredicates.setSelectedPartitionIds(predicates.getSelectedPartitionIds());
                scanNodePredicates.setIdToPartitionKey(predicates.getIdToPartitionKey());

                hudiScanNode.setupScanRangeLocations(context.getDescTbl());

                prepareCommonExpr(scanNodePredicates, predicates, context);
                prepareMinMaxExpr(scanNodePredicates, predicates, context);

            } catch (Exception e) {
                LOG.warn("Hudi scan node get scan range locations failed : " + e);
                e.printStackTrace();
                throw new StarRocksPlannerException(e.getMessage(), INTERNAL_ERROR);
            }

            hudiScanNode.setLimit(node.getLimit());

            tupleDescriptor.computeMemLayout();
            context.getScanNodes().add(hudiScanNode);

            PlanFragment fragment =
                    new PlanFragment(context.getNextFragmentId(), hudiScanNode, DataPartition.RANDOM);
            context.getFragments().add(fragment);
            return fragment;
        }

        @Override
        public PlanFragment visitPhysicalHiveScan(OptExpression optExpression, ExecPlan context) {
            PhysicalHiveScanOperator node = (PhysicalHiveScanOperator) optExpression.getOp();
            ScanOperatorPredicates predicates = node.getScanOperatorPredicates();

            Table referenceTable = node.getTable();
            context.getDescTbl().addReferencedTable(referenceTable);
            TupleDescriptor tupleDescriptor = context.getDescTbl().createTupleDescriptor();
            tupleDescriptor.setTable(referenceTable);

            prepareContextSlots(node, context, tupleDescriptor);

            HdfsScanNode hdfsScanNode =
                    new HdfsScanNode(context.getNextNodeId(), tupleDescriptor, "HdfsScanNode");
            hdfsScanNode.computeStatistics(optExpression.getStatistics());
            try {
                HDFSScanNodePredicates scanNodePredicates = hdfsScanNode.getPredictsExpr();
                scanNodePredicates.setSelectedPartitionIds(predicates.getSelectedPartitionIds());
                scanNodePredicates.setIdToPartitionKey(predicates.getIdToPartitionKey());

                hdfsScanNode.setupScanRangeLocations(context.getDescTbl());

                prepareCommonExpr(scanNodePredicates, predicates, context);
                prepareMinMaxExpr(scanNodePredicates, predicates, context);
            } catch (Exception e) {
                LOG.warn("Hdfs scan node get scan range locations failed : " + e);
                throw new StarRocksPlannerException(e.getMessage(), INTERNAL_ERROR);
            }

            hdfsScanNode.setLimit(node.getLimit());

            tupleDescriptor.computeMemLayout();
            context.getScanNodes().add(hdfsScanNode);

            PlanFragment fragment =
                    new PlanFragment(context.getNextFragmentId(), hdfsScanNode, DataPartition.RANDOM);
            context.getFragments().add(fragment);
            return fragment;
        }

        @Override
        public PlanFragment visitPhysicalIcebergScan(OptExpression optExpression, ExecPlan context) {
            PhysicalIcebergScanOperator node = (PhysicalIcebergScanOperator) optExpression.getOp();

            Table referenceTable = node.getTable();
            context.getDescTbl().addReferencedTable(referenceTable);
            TupleDescriptor tupleDescriptor = context.getDescTbl().createTupleDescriptor();
            tupleDescriptor.setTable(referenceTable);

            // set slot
            for (Map.Entry<ColumnRefOperator, Column> entry : node.getColRefToColumnMetaMap().entrySet()) {
                SlotDescriptor slotDescriptor =
                        context.getDescTbl().addSlotDescriptor(tupleDescriptor, new SlotId(entry.getKey().getId()));
                slotDescriptor.setColumn(entry.getValue());
                slotDescriptor.setIsNullable(entry.getValue().isAllowNull());
                slotDescriptor.setIsMaterialized(true);
                context.getColRefToExpr().put(entry.getKey(), new SlotRef(entry.getKey().toString(), slotDescriptor));
            }

            IcebergScanNode icebergScanNode =
                    new IcebergScanNode(context.getNextNodeId(), tupleDescriptor, "IcebergScanNode");
            icebergScanNode.computeStatistics(optExpression.getStatistics());
            try {
                // set predicate
                ScalarOperatorToExpr.FormatterContext formatterContext =
                        new ScalarOperatorToExpr.FormatterContext(context.getColRefToExpr());
                List<ScalarOperator> predicates = Utils.extractConjuncts(node.getPredicate());
                for (ScalarOperator predicate : predicates) {
                    icebergScanNode.getConjuncts()
                            .add(ScalarOperatorToExpr.buildExecExpression(predicate, formatterContext));
                }
                icebergScanNode.getScanRangeLocations();
                /*
                 * populates 'minMaxTuple' with slots for statistics values,
                 * and populates 'minMaxConjuncts' with conjuncts pointing into the 'minMaxTuple'
                 */
                List<ScalarOperator> minMaxConjuncts = node.getMinMaxConjuncts();
                TupleDescriptor minMaxTuple = context.getDescTbl().createTupleDescriptor();
                for (ScalarOperator minMaxConjunct : minMaxConjuncts) {
                    for (ColumnRefOperator columnRefOperator : Utils.extractColumnRef(minMaxConjunct)) {
                        SlotDescriptor slotDescriptor =
                                context.getDescTbl()
                                        .addSlotDescriptor(minMaxTuple, new SlotId(columnRefOperator.getId()));
                        Column column = node.getMinMaxColumnRefMap().get(columnRefOperator);
                        slotDescriptor.setColumn(column);
                        slotDescriptor.setIsNullable(column.isAllowNull());
                        slotDescriptor.setIsMaterialized(true);
                        context.getColRefToExpr()
                                .put(columnRefOperator, new SlotRef(columnRefOperator.toString(), slotDescriptor));
                    }
                }
                minMaxTuple.computeMemLayout();
                icebergScanNode.setMinMaxTuple(minMaxTuple);
                ScalarOperatorToExpr.FormatterContext minMaxFormatterContext =
                        new ScalarOperatorToExpr.FormatterContext(context.getColRefToExpr());
                for (ScalarOperator minMaxConjunct : minMaxConjuncts) {
                    icebergScanNode.getMinMaxConjuncts().
                            add(ScalarOperatorToExpr.buildExecExpression(minMaxConjunct, minMaxFormatterContext));
                }
            } catch (UserException e) {
                LOG.warn("Iceberg scan node get scan range locations failed : " + e);
                throw new StarRocksPlannerException(e.getMessage(), INTERNAL_ERROR);
            }

            icebergScanNode.setLimit(node.getLimit());

            tupleDescriptor.computeMemLayout();
            context.getScanNodes().add(icebergScanNode);

            PlanFragment fragment =
                    new PlanFragment(context.getNextFragmentId(), icebergScanNode, DataPartition.RANDOM);
            context.getFragments().add(fragment);
            return fragment;
        }

        @Override
        public PlanFragment visitPhysicalSchemaScan(OptExpression optExpression, ExecPlan context) {
            PhysicalSchemaScanOperator node = (PhysicalSchemaScanOperator) optExpression.getOp();

            context.getDescTbl().addReferencedTable(node.getTable());
            TupleDescriptor tupleDescriptor = context.getDescTbl().createTupleDescriptor();
            tupleDescriptor.setTable(node.getTable());

            for (Map.Entry<ColumnRefOperator, Column> entry : node.getColRefToColumnMetaMap().entrySet()) {
                SlotDescriptor slotDescriptor =
                        context.getDescTbl().addSlotDescriptor(tupleDescriptor, new SlotId(entry.getKey().getId()));
                slotDescriptor.setColumn(entry.getValue());
                slotDescriptor.setIsNullable(entry.getValue().isAllowNull());
                slotDescriptor.setIsMaterialized(true);
                context.getColRefToExpr().put(entry.getKey(), new SlotRef(entry.getKey().toString(), slotDescriptor));
            }

            tupleDescriptor.computeMemLayout();

            SchemaScanNode scanNode = new SchemaScanNode(context.getNextNodeId(), tupleDescriptor);

            scanNode.setFrontendIP(FrontendOptions.getLocalHostAddress());
            scanNode.setFrontendPort(Config.rpc_port);
            scanNode.setUser(context.getConnectContext().getQualifiedUser());
            scanNode.setUserIp(context.getConnectContext().getRemoteIP());
            scanNode.setLimit(node.getLimit());

            // set predicate
            List<ScalarOperator> predicates = Utils.extractConjuncts(node.getPredicate());
            ScalarOperatorToExpr.FormatterContext formatterContext =
                    new ScalarOperatorToExpr.FormatterContext(context.getColRefToExpr());

            for (ScalarOperator predicate : predicates) {
                scanNode.getConjuncts().add(ScalarOperatorToExpr.buildExecExpression(predicate, formatterContext));
            }

            context.getScanNodes().add(scanNode);
            PlanFragment fragment =
                    new PlanFragment(context.getNextFragmentId(), scanNode, DataPartition.UNPARTITIONED);
            context.getFragments().add(fragment);
            return fragment;
        }

        @Override
        public PlanFragment visitPhysicalMysqlScan(OptExpression optExpression, ExecPlan context) {
            PhysicalMysqlScanOperator node = (PhysicalMysqlScanOperator) optExpression.getOp();

            context.getDescTbl().addReferencedTable(node.getTable());
            TupleDescriptor tupleDescriptor = context.getDescTbl().createTupleDescriptor();
            tupleDescriptor.setTable(node.getTable());

            for (Map.Entry<ColumnRefOperator, Column> entry : node.getColRefToColumnMetaMap().entrySet()) {
                SlotDescriptor slotDescriptor =
                        context.getDescTbl().addSlotDescriptor(tupleDescriptor, new SlotId(entry.getKey().getId()));
                slotDescriptor.setColumn(entry.getValue());
                slotDescriptor.setIsNullable(entry.getValue().isAllowNull());
                slotDescriptor.setIsMaterialized(true);
                context.getColRefToExpr().put(entry.getKey(), new SlotRef(entry.getKey().getName(), slotDescriptor));
            }
            tupleDescriptor.computeMemLayout();

            MysqlScanNode scanNode = new MysqlScanNode(context.getNextNodeId(), tupleDescriptor,
                    (MysqlTable) node.getTable());

            // set predicate
            List<ScalarOperator> predicates = Utils.extractConjuncts(node.getPredicate());
            ScalarOperatorToExpr.FormatterContext formatterContext =
                    new ScalarOperatorToExpr.FormatterContext(context.getColRefToExpr());
            formatterContext.setImplicitCast(true);
            for (ScalarOperator predicate : predicates) {
                scanNode.getConjuncts().add(ScalarOperatorToExpr.buildExecExpression(predicate, formatterContext));
            }

            scanNode.setLimit(node.getLimit());
            scanNode.computeColumnsAndFilters();
            scanNode.computeStatistics(optExpression.getStatistics());

            context.getScanNodes().add(scanNode);
            PlanFragment fragment =
                    new PlanFragment(context.getNextFragmentId(), scanNode, DataPartition.UNPARTITIONED);
            context.getFragments().add(fragment);
            return fragment;
        }

        @Override
        public PlanFragment visitPhysicalEsScan(OptExpression optExpression, ExecPlan context) {
            PhysicalEsScanOperator node = (PhysicalEsScanOperator) optExpression.getOp();

            context.getDescTbl().addReferencedTable(node.getTable());
            TupleDescriptor tupleDescriptor = context.getDescTbl().createTupleDescriptor();
            tupleDescriptor.setTable(node.getTable());

            for (Map.Entry<ColumnRefOperator, Column> entry : node.getColRefToColumnMetaMap().entrySet()) {
                SlotDescriptor slotDescriptor =
                        context.getDescTbl().addSlotDescriptor(tupleDescriptor, new SlotId(entry.getKey().getId()));
                slotDescriptor.setColumn(entry.getValue());
                slotDescriptor.setIsNullable(entry.getValue().isAllowNull());
                slotDescriptor.setIsMaterialized(true);
                context.getColRefToExpr().put(entry.getKey(), new SlotRef(entry.getKey().toString(), slotDescriptor));
            }
            tupleDescriptor.computeMemLayout();

            EsScanNode scanNode = new EsScanNode(context.getNextNodeId(), tupleDescriptor, "EsScanNode");
            // set predicate
            List<ScalarOperator> predicates = Utils.extractConjuncts(node.getPredicate());
            ScalarOperatorToExpr.FormatterContext formatterContext =
                    new ScalarOperatorToExpr.FormatterContext(context.getColRefToExpr());

            for (ScalarOperator predicate : predicates) {
                scanNode.getConjuncts().add(ScalarOperatorToExpr.buildExecExpression(predicate, formatterContext));
            }
            scanNode.setLimit(node.getLimit());
            scanNode.computeStatistics(optExpression.getStatistics());
            try {
                scanNode.assignBackends();
            } catch (UserException e) {
                throw new StarRocksPlannerException(e.getMessage(), INTERNAL_ERROR);
            }
            scanNode.setShardScanRanges(scanNode.computeShardLocations(node.getSelectedIndex()));

            context.getScanNodes().add(scanNode);
            PlanFragment fragment =
                    new PlanFragment(context.getNextFragmentId(), scanNode, DataPartition.RANDOM);
            context.getFragments().add(fragment);
            return fragment;
        }

        @Override
        public PlanFragment visitPhysicalJDBCScan(OptExpression optExpression, ExecPlan context) {
            PhysicalJDBCScanOperator node = (PhysicalJDBCScanOperator) optExpression.getOp();

            context.getDescTbl().addReferencedTable(node.getTable());
            TupleDescriptor tupleDescriptor = context.getDescTbl().createTupleDescriptor();
            tupleDescriptor.setTable(node.getTable());

            for (Map.Entry<ColumnRefOperator, Column> entry : node.getColRefToColumnMetaMap().entrySet()) {
                SlotDescriptor slotDescriptor =
                        context.getDescTbl().addSlotDescriptor(tupleDescriptor, new SlotId(entry.getKey().getId()));
                slotDescriptor.setColumn(entry.getValue());
                slotDescriptor.setIsNullable(entry.getValue().isAllowNull());
                slotDescriptor.setIsMaterialized(true);
                context.getColRefToExpr().put(entry.getKey(), new SlotRef(entry.getKey().getName(), slotDescriptor));
            }
            tupleDescriptor.computeMemLayout();

            JDBCScanNode scanNode = new JDBCScanNode(context.getNextNodeId(), tupleDescriptor,
                    (JDBCTable) node.getTable());

            // set predicate
            List<ScalarOperator> predicates = Utils.extractConjuncts(node.getPredicate());
            ScalarOperatorToExpr.FormatterContext formatterContext =
                    new ScalarOperatorToExpr.FormatterContext(context.getColRefToExpr());
            formatterContext.setImplicitCast(true);
            for (ScalarOperator predicate : predicates) {
                scanNode.getConjuncts().add(ScalarOperatorToExpr.buildExecExpression(predicate, formatterContext));
            }

            scanNode.setLimit(node.getLimit());
            scanNode.computeColumnsAndFilters();
            scanNode.computeStatistics(optExpression.getStatistics());

            context.getScanNodes().add(scanNode);
            PlanFragment fragment =
                    new PlanFragment(context.getNextFragmentId(), scanNode, DataPartition.UNPARTITIONED);
            context.getFragments().add(fragment);
            return fragment;
        }

        @Override
        public PlanFragment visitPhysicalValues(OptExpression optExpr, ExecPlan context) {
            PhysicalValuesOperator valuesOperator = (PhysicalValuesOperator) optExpr.getOp();

            TupleDescriptor tupleDescriptor = context.getDescTbl().createTupleDescriptor();
            for (ColumnRefOperator columnRefOperator : valuesOperator.getColumnRefSet()) {
                SlotDescriptor slotDescriptor =
                        context.getDescTbl().addSlotDescriptor(tupleDescriptor, new SlotId(columnRefOperator.getId()));
                slotDescriptor.setIsNullable(columnRefOperator.isNullable());
                slotDescriptor.setIsMaterialized(true);
                slotDescriptor.setType(columnRefOperator.getType());
                context.getColRefToExpr()
                        .put(columnRefOperator, new SlotRef(columnRefOperator.toString(), slotDescriptor));
            }
            tupleDescriptor.computeMemLayout();

            if (valuesOperator.getRows().isEmpty()) {
                EmptySetNode emptyNode = new EmptySetNode(context.getNextNodeId(),
                        Lists.newArrayList(tupleDescriptor.getId()));
                emptyNode.computeStatistics(optExpr.getStatistics());
                PlanFragment fragment = new PlanFragment(context.getNextFragmentId(), emptyNode,
                        DataPartition.UNPARTITIONED);
                context.getFragments().add(fragment);
                return fragment;
            } else {
                UnionNode unionNode = new UnionNode(context.getNextNodeId(), tupleDescriptor.getId());
                unionNode.setLimit(valuesOperator.getLimit());

                List<List<Expr>> consts = new ArrayList<>();
                for (List<ScalarOperator> row : valuesOperator.getRows()) {
                    List<Expr> exprRow = new ArrayList<>();
                    for (ScalarOperator field : row) {
                        exprRow.add(ScalarOperatorToExpr.buildExecExpression(
                                field, new ScalarOperatorToExpr.FormatterContext(context.getColRefToExpr())));
                    }
                    consts.add(exprRow);
                }

                unionNode.setMaterializedConstExprLists_(consts);
                unionNode.computeStatistics(optExpr.getStatistics());
                /*
                 * TODO(lhy):
                 * It doesn't make sense for vectorized execution engines, but it will appear in explain.
                 * we can delete this when refactoring explain in the future,
                 */
                consts.forEach(unionNode::addConstExprList);

                PlanFragment fragment = new PlanFragment(context.getNextFragmentId(), unionNode,
                        DataPartition.UNPARTITIONED);
                context.getFragments().add(fragment);
                return fragment;
            }
        }

        // return true if all leaf offspring are not ExchangeNode
        public static boolean hasNoExchangeNodes(PlanNode root) {
            if (root instanceof ExchangeNode) {
                return false;
            }
            for (PlanNode childNode : root.getChildren()) {
                if (!hasNoExchangeNodes(childNode)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public PlanFragment visitPhysicalHashAggregate(OptExpression optExpr, ExecPlan context) {
            PhysicalHashAggregateOperator node = (PhysicalHashAggregateOperator) optExpr.getOp();
            PlanFragment inputFragment = visit(optExpr.inputAt(0), context);

            /*
             * Create aggregate TupleDescriptor
             */
            TupleDescriptor outputTupleDesc = context.getDescTbl().createTupleDescriptor();

            ArrayList<Expr> groupingExpressions = Lists.newArrayList();
            for (ColumnRefOperator grouping : node.getGroupBys()) {
                Expr groupingExpr = ScalarOperatorToExpr.buildExecExpression(grouping,
                        new ScalarOperatorToExpr.FormatterContext(context.getColRefToExpr()));

                groupingExpressions.add(groupingExpr);

                SlotDescriptor slotDesc =
                        context.getDescTbl().addSlotDescriptor(outputTupleDesc, new SlotId(grouping.getId()));
                slotDesc.setType(groupingExpr.getType());
                slotDesc.setIsNullable(groupingExpr.isNullable());
                slotDesc.setIsMaterialized(true);
                context.getColRefToExpr().put(grouping, new SlotRef(grouping.toString(), slotDesc));
            }

            ArrayList<FunctionCallExpr> aggregateExprList = Lists.newArrayList();
            for (Map.Entry<ColumnRefOperator, CallOperator> aggregation : node.getAggregations().entrySet()) {
                FunctionCallExpr aggExpr = (FunctionCallExpr) ScalarOperatorToExpr.buildExecExpression(
                        aggregation.getValue(), new ScalarOperatorToExpr.FormatterContext(context.getColRefToExpr()));

                aggregateExprList.add(aggExpr);

                SlotDescriptor slotDesc = context.getDescTbl()
                        .addSlotDescriptor(outputTupleDesc, new SlotId(aggregation.getKey().getId()));
                slotDesc.setType(aggregation.getValue().getType());
                slotDesc.setIsNullable(aggExpr.isNullable());
                slotDesc.setIsMaterialized(true);
                context.getColRefToExpr()
                        .put(aggregation.getKey(), new SlotRef(aggregation.getKey().toString(), slotDesc));
            }

            List<Expr> partitionExpressions = Lists.newArrayList();
            for (ColumnRefOperator column : node.getPartitionByColumns()) {
                Expr partitionExpr = ScalarOperatorToExpr.buildExecExpression(column,
                        new ScalarOperatorToExpr.FormatterContext(context.getColRefToExpr()));

                SlotDescriptor slotDesc =
                        context.getDescTbl().addSlotDescriptor(outputTupleDesc, new SlotId(column.getId()));
                slotDesc.setType(partitionExpr.getType());
                slotDesc.setIsNullable(partitionExpr.isNullable());
                slotDesc.setIsMaterialized(true);
                context.getColRefToExpr().put(column, new SlotRef(column.toString(), slotDesc));

                partitionExpressions.add(new SlotRef(slotDesc));
            }

            outputTupleDesc.computeMemLayout();

            AggregationNode aggregationNode;
            if (node.getType().isLocal()) {
                AggregateInfo aggInfo = AggregateInfo.create(
                        groupingExpressions,
                        aggregateExprList,
                        outputTupleDesc, outputTupleDesc,
                        AggregateInfo.AggPhase.FIRST);
                aggregationNode =
                        new AggregationNode(context.getNextNodeId(), inputFragment.getPlanRoot(), aggInfo);
                aggregationNode.unsetNeedsFinalize();
                aggregationNode.setIsPreagg(node.isUseStreamingPreAgg());
                aggregationNode.setIntermediateTuple();

                if (!partitionExpressions.isEmpty()) {
                    inputFragment.setOutputPartition(DataPartition.hashPartitioned(partitionExpressions));
                }
            } else if (node.getType().isGlobal()) {
                if (node.hasSingleDistinct()) {
                    // For SQL: select count(id_int) as a, sum(DISTINCT id_bigint) as b from test_basic group by id_int;
                    // sum function is update function, but count is merge function
                    for (int i = 0; i < aggregateExprList.size(); i++) {
                        if (i != node.getSingleDistinctFunctionPos()) {
                            aggregateExprList.get(i).setMergeAggFn();
                        }
                    }

                    AggregateInfo aggInfo = AggregateInfo.create(
                            groupingExpressions,
                            aggregateExprList,
                            outputTupleDesc, outputTupleDesc,
                            AggregateInfo.AggPhase.SECOND);
                    aggregationNode =
                            new AggregationNode(context.getNextNodeId(), inputFragment.getPlanRoot(),
                                    aggInfo);
                } else if (!node.isSplit()) {
                    rewriteAggDistinctFirstStageFunction(aggregateExprList);
                    AggregateInfo aggInfo = AggregateInfo.create(
                            groupingExpressions,
                            aggregateExprList,
                            outputTupleDesc, outputTupleDesc,
                            AggregateInfo.AggPhase.FIRST);
                    aggregationNode =
                            new AggregationNode(context.getNextNodeId(), inputFragment.getPlanRoot(),
                                    aggInfo);
                } else {
                    aggregateExprList.forEach(FunctionCallExpr::setMergeAggFn);
                    AggregateInfo aggInfo = AggregateInfo.create(
                            groupingExpressions,
                            aggregateExprList,
                            outputTupleDesc, outputTupleDesc,
                            AggregateInfo.AggPhase.SECOND_MERGE);
                    aggregationNode =
                            new AggregationNode(context.getNextNodeId(), inputFragment.getPlanRoot(),
                                    aggInfo);
                }
                // set aggregate node can use local aggregate
                if (hasColocateOlapScanChildInFragment(aggregationNode)) {
                    aggregationNode.setColocate(true);
                }

                // set predicate
                List<ScalarOperator> predicates = Utils.extractConjuncts(node.getPredicate());
                ScalarOperatorToExpr.FormatterContext formatterContext =
                        new ScalarOperatorToExpr.FormatterContext(context.getColRefToExpr());

                for (ScalarOperator predicate : predicates) {
                    aggregationNode.getConjuncts()
                            .add(ScalarOperatorToExpr.buildExecExpression(predicate, formatterContext));
                }
                aggregationNode.setLimit(node.getLimit());
            } else if (node.getType().isDistinctGlobal()) {
                aggregateExprList.forEach(FunctionCallExpr::setMergeAggFn);
                AggregateInfo aggInfo = AggregateInfo.create(
                        groupingExpressions,
                        aggregateExprList,
                        outputTupleDesc, outputTupleDesc,
                        AggregateInfo.AggPhase.FIRST_MERGE);
                aggregationNode =
                        new AggregationNode(context.getNextNodeId(), inputFragment.getPlanRoot(), aggInfo);
                aggregationNode.unsetNeedsFinalize();
                aggregationNode.setIntermediateTuple();
            } else if (node.getType().isDistinctLocal()) {
                // For SQL: select count(distinct id_bigint), sum(id_int) from test_basic;
                // count function is update function, but sum is merge function
                for (int i = 0; i < aggregateExprList.size(); i++) {
                    if (i != node.getSingleDistinctFunctionPos()) {
                        aggregateExprList.get(i).setMergeAggFn();
                    }
                }

                AggregateInfo aggInfo = AggregateInfo.create(
                        groupingExpressions,
                        aggregateExprList,
                        outputTupleDesc, outputTupleDesc,
                        AggregateInfo.AggPhase.SECOND);
                aggregationNode =
                        new AggregationNode(context.getNextNodeId(), inputFragment.getPlanRoot(), aggInfo);
                aggregationNode.unsetNeedsFinalize();
                aggregationNode.setIsPreagg(node.isUseStreamingPreAgg());
                aggregationNode.setIntermediateTuple();
            } else {
                throw unsupportedException("Not support aggregate type : " + node.getType());
            }

            aggregationNode.setStreamingPreaggregationMode(context.getConnectContext().
                    getSessionVariable().getStreamingPreaggregationMode());
            aggregationNode.setHasNullableGenerateChild();
            aggregationNode.computeStatistics(optExpr.getStatistics());

            boolean notNeedLocalShuffle = aggregationNode.isNeedsFinalize() &&
                    hasNoExchangeNodes(inputFragment.getPlanRoot());
            boolean pipelineDopEnabled = ConnectContext.get() != null &&
                    ConnectContext.get().getSessionVariable().isPipelineDopAdaptionEnabled() &&
                    inputFragment.getPlanRoot().canUsePipeLine();
            if (pipelineDopEnabled && notNeedLocalShuffle) {
                inputFragment.setNeedsLocalShuffle(false);
            }

            inputFragment.setPlanRoot(aggregationNode);
            return inputFragment;
        }

        // Check whether colocate Table exists in the same Fragment
        public boolean hasColocateOlapScanChildInFragment(PlanNode node) {
            if (node instanceof OlapScanNode) {
                ColocateTableIndex colocateIndex = Catalog.getCurrentColocateIndex();
                OlapScanNode scanNode = (OlapScanNode) node;
                if (colocateIndex.isColocateTable(scanNode.getOlapTable().getId())) {
                    return true;
                }
            }
            if (node instanceof ExchangeNode) {
                return false;
            }
            boolean hasOlapScanChild = false;
            for (PlanNode child : node.getChildren()) {
                hasOlapScanChild |= hasColocateOlapScanChildInFragment(child);
            }
            return hasOlapScanChild;
        }

        public void rewriteAggDistinctFirstStageFunction(List<FunctionCallExpr> aggregateExprList) {
            int singleDistinctCount = 0;
            int singleDistinctIndex = 0;
            FunctionCallExpr functionCallExpr = null;
            for (int i = 0; i < aggregateExprList.size(); ++i) {
                FunctionCallExpr callExpr = aggregateExprList.get(i);
                if (callExpr.isDistinct()) {
                    ++singleDistinctCount;
                    functionCallExpr = callExpr;
                    singleDistinctIndex = i;
                }
            }
            if (singleDistinctCount == 1) {
                FunctionCallExpr replaceExpr = null;
                final String functionName = functionCallExpr.getFnName().getFunction();
                if (functionName.equalsIgnoreCase(FunctionSet.COUNT)) {
                    replaceExpr = new FunctionCallExpr(FunctionSet.MULTI_DISTINCT_COUNT, functionCallExpr.getParams());
                    replaceExpr.setFn(Expr.getBuiltinFunction(FunctionSet.MULTI_DISTINCT_COUNT,
                            new Type[] {functionCallExpr.getChild(0).getType()},
                            IS_NONSTRICT_SUPERTYPE_OF));
                    replaceExpr.getParams().setIsDistinct(false);
                } else if (functionName.equalsIgnoreCase("SUM")) {
                    replaceExpr = new FunctionCallExpr(FunctionSet.MULTI_DISTINCT_SUM, functionCallExpr.getParams());
                    replaceExpr.setFn(Expr.getBuiltinFunction(FunctionSet.MULTI_DISTINCT_SUM,
                            new Type[] {functionCallExpr.getChild(0).getType()},
                            IS_NONSTRICT_SUPERTYPE_OF));
                    replaceExpr.getParams().setIsDistinct(false);
                }
                Preconditions.checkState(replaceExpr != null);
                replaceExpr.analyzeNoThrow(null);

                aggregateExprList.set(singleDistinctIndex, replaceExpr);
            }
        }

        @Override
        public PlanFragment visitPhysicalDistribution(OptExpression optExpr, ExecPlan context) {
            PlanFragment inputFragment = visit(optExpr.inputAt(0), context);
            PhysicalDistributionOperator distribution = (PhysicalDistributionOperator) optExpr.getOp();

            ExchangeNode exchangeNode = new ExchangeNode(context.getNextNodeId(),
                    inputFragment.getPlanRoot(), false, distribution.getDistributionSpec().getType());

            DataPartition dataPartition;
            if (DistributionSpec.DistributionType.GATHER.equals(distribution.getDistributionSpec().getType())) {
                exchangeNode.setNumInstances(1);
                dataPartition = DataPartition.UNPARTITIONED;
                GatherDistributionSpec spec = (GatherDistributionSpec) distribution.getDistributionSpec();
                if (spec.hasLimit()) {
                    exchangeNode.setLimit(spec.getLimit());
                }
            } else if (DistributionSpec.DistributionType.BROADCAST
                    .equals(distribution.getDistributionSpec().getType())) {
                exchangeNode.setNumInstances(inputFragment.getPlanRoot().getNumInstances());
                dataPartition = DataPartition.UNPARTITIONED;
            } else if (DistributionSpec.DistributionType.SHUFFLE.equals(distribution.getDistributionSpec().getType())) {
                exchangeNode.setNumInstances(inputFragment.getPlanRoot().getNumInstances());
                List<Integer> columnRefSet =
                        ((HashDistributionSpec) distribution.getDistributionSpec()).getHashDistributionDesc()
                                .getColumns();
                Preconditions.checkState(!columnRefSet.isEmpty());
                List<ColumnRefOperator> partitionColumns = new ArrayList<>();
                for (int columnId : columnRefSet) {
                    partitionColumns.add(columnRefFactory.getColumnRef(columnId));
                }
                List<Expr> distributeExpressions =
                        partitionColumns.stream().map(e -> ScalarOperatorToExpr.buildExecExpression(e,
                                        new ScalarOperatorToExpr.FormatterContext(context.getColRefToExpr())))
                                .collect(Collectors.toList());
                dataPartition = DataPartition.hashPartitioned(distributeExpressions);
            } else {
                throw new StarRocksPlannerException("Unsupport exchange type : "
                        + distribution.getDistributionSpec().getType(), INTERNAL_ERROR);
            }

            PlanFragment fragment =
                    new PlanFragment(context.getNextFragmentId(), exchangeNode, dataPartition);
            fragment.setQueryGlobalDicts(distribution.getGlobalDicts());
            inputFragment.setDestination(exchangeNode);
            inputFragment.setOutputPartition(dataPartition);

            context.getFragments().add(fragment);
            return fragment;
        }

        @Override
        public PlanFragment visitPhysicalTopN(OptExpression optExpr, ExecPlan context) {
            PlanFragment inputFragment = visit(optExpr.inputAt(0), context);
            PhysicalTopNOperator topN = (PhysicalTopNOperator) optExpr.getOp();
            Preconditions.checkState(topN.getOffset() >= 0);
            if (!topN.isSplit()) {
                return buildPartialTopNFragment(optExpr, context, topN.getOrderSpec(), topN.getLimit(),
                        topN.getOffset(),
                        inputFragment);
            } else {
                return buildFinalTopNFragment(context, topN.getLimit(), topN.getOffset(), inputFragment, optExpr);
            }
        }

        private PlanFragment buildFinalTopNFragment(ExecPlan context, long limit, long offset,
                                                    PlanFragment inputFragment,
                                                    OptExpression optExpr) {
            ExchangeNode exchangeNode = new ExchangeNode(context.getNextNodeId(),
                    inputFragment.getPlanRoot(), false,
                    DistributionSpec.DistributionType.GATHER);

            exchangeNode.setNumInstances(1);
            DataPartition dataPartition = DataPartition.UNPARTITIONED;

            Preconditions.checkState(inputFragment.getPlanRoot() instanceof SortNode);
            SortNode sortNode = (SortNode) inputFragment.getPlanRoot();
            exchangeNode.setMergeInfo(sortNode.getSortInfo(), offset);
            exchangeNode.computeStatistics(optExpr.getStatistics());
            exchangeNode.setLimit(limit);

            PlanFragment fragment =
                    new PlanFragment(context.getNextFragmentId(), exchangeNode, dataPartition);
            inputFragment.setDestination(exchangeNode);
            inputFragment.setOutputPartition(dataPartition);

            context.getFragments().add(fragment);
            return fragment;
        }

        private PlanFragment buildPartialTopNFragment(OptExpression optExpr, ExecPlan context,
                                                      OrderSpec orderSpec, long limit, long offset,
                                                      PlanFragment inputFragment) {
            List<Expr> resolvedTupleExprs = new ArrayList<>();
            List<Expr> sortExprs = new ArrayList<>();
            TupleDescriptor sortTuple = context.getDescTbl().createTupleDescriptor();

            for (Ordering ordering : orderSpec.getOrderDescs()) {
                Expr sortExpr = ScalarOperatorToExpr.buildExecExpression(ordering.getColumnRef(),
                        new ScalarOperatorToExpr.FormatterContext(context.getColRefToExpr()));

                SlotDescriptor slotDesc =
                        context.getDescTbl().addSlotDescriptor(sortTuple, new SlotId(ordering.getColumnRef().getId()));
                slotDesc.initFromExpr(sortExpr);
                slotDesc.setIsMaterialized(true);
                slotDesc.setIsNullable(sortExpr.isNullable());
                slotDesc.setType(sortExpr.getType());

                context.getColRefToExpr()
                        .put(ordering.getColumnRef(), new SlotRef(ordering.getColumnRef().toString(), slotDesc));
                resolvedTupleExprs.add(sortExpr);
                sortExprs.add(new SlotRef(slotDesc));
            }

            ColumnRefSet columnRefSet = optExpr.getLogicalProperty().getOutputColumns();
            for (int i = 0; i < columnRefSet.getColumnIds().length; ++i) {
                /*
                 * Add column not be used in ordering
                 */
                ColumnRefOperator columnRef = columnRefFactory.getColumnRef(columnRefSet.getColumnIds()[i]);
                if (orderSpec.getOrderDescs().stream().map(Ordering::getColumnRef)
                        .noneMatch(c -> c.equals(columnRef))) {
                    Expr outputExpr = ScalarOperatorToExpr.buildExecExpression(columnRef,
                            new ScalarOperatorToExpr.FormatterContext(context.getColRefToExpr()));

                    SlotDescriptor slotDesc =
                            context.getDescTbl().addSlotDescriptor(sortTuple, new SlotId(columnRef.getId()));
                    slotDesc.initFromExpr(outputExpr);
                    slotDesc.setIsMaterialized(true);
                    slotDesc.setIsNullable(outputExpr.isNullable());
                    slotDesc.setType(outputExpr.getType());

                    context.getColRefToExpr().put(columnRef, new SlotRef(columnRef.toString(), slotDesc));
                    resolvedTupleExprs.add(outputExpr);
                }
            }

            sortTuple.computeMemLayout();
            SortInfo sortInfo = new SortInfo(
                    sortExprs,
                    orderSpec.getOrderDescs().stream().map(Ordering::isAscending).collect(Collectors.toList()),
                    orderSpec.getOrderDescs().stream().map(Ordering::isNullsFirst).collect(Collectors.toList()));
            sortInfo.setMaterializedTupleInfo(sortTuple, resolvedTupleExprs);

            SortNode sortNode = new SortNode(
                    context.getNextNodeId(),
                    inputFragment.getPlanRoot(),
                    sortInfo,
                    limit != Operator.DEFAULT_LIMIT,
                    limit == Operator.DEFAULT_LIMIT,
                    0);
            sortNode.setLimit(limit);
            sortNode.setOffset(offset);
            sortNode.resolvedTupleExprs = resolvedTupleExprs;
            sortNode.setHasNullableGenerateChild();
            sortNode.computeStatistics(optExpr.getStatistics());

            inputFragment.setPlanRoot(sortNode);
            return inputFragment;
        }

        private void setJoinPushDown(HashJoinNode node) {
            // Push down the predicates constructed by the right child when the
            // join op is inner join or left semi join or right join(semi, outer, anti)
            if (ConnectContext.get().getSessionVariable().isHashJoinPushDownRightTable()
                    && (node.getJoinOp().isInnerJoin() || node.getJoinOp().isLeftSemiJoin() ||
                    node.getJoinOp().isRightJoin())) {
                node.setIsPushDown(true);
            } else {
                node.setIsPushDown(false);
            }
        }

        private void estimateDopOfBroadcastJoinInPipeline(PlanFragment fragment) {
            if (ConnectContext.get() == null ||
                    !ConnectContext.get().getSessionVariable().isEnablePipelineEngine() ||
                    ConnectContext.get().getSessionVariable().getPipelineDop() > 0) {
                return;
            }
            if (fragment.isDopEstimated()) {
                return;
            }
            Preconditions.checkArgument(fragment.getPlanRoot() instanceof HashJoinNode);
            HashJoinNode hashJoinNode = (HashJoinNode) fragment.getPlanRoot();
            HashJoinNode.DistributionMode distributionMode = hashJoinNode.getDistributionMode();
            if (!distributionMode.equals(HashJoinNode.DistributionMode.BROADCAST) &&
                    !distributionMode.equals(HashJoinNode.DistributionMode.REPLICATED)) {
                return;
            }
            fragment.setPipelineDop(fragment.getParallelExecNum());
            fragment.setParallelExecNum(1);
            fragment.setDopEstimated();
        }

        @Override
        public PlanFragment visitPhysicalHashJoin(OptExpression optExpr, ExecPlan context) {
            PlanFragment leftFragment = visit(optExpr.inputAt(0), context);
            PlanFragment rightFragment = visit(optExpr.inputAt(1), context);
            PhysicalHashJoinOperator node = (PhysicalHashJoinOperator) optExpr.getOp();

            ColumnRefSet leftChildColumns = optExpr.inputAt(0).getLogicalProperty().getOutputColumns();
            ColumnRefSet rightChildColumns = optExpr.inputAt(1).getLogicalProperty().getOutputColumns();

            // 2. Get eqJoinConjuncts
            List<BinaryPredicateOperator> eqOnPredicates = getEqConj(
                    leftChildColumns,
                    rightChildColumns,
                    Utils.extractConjuncts(node.getOnPredicate()));

            if (node.getJoinType().isCrossJoin() ||
                    (node.getJoinType().isInnerJoin() && eqOnPredicates.isEmpty())) {
                CrossJoinNode joinNode = new CrossJoinNode(context.getNextNodeId(),
                        leftFragment.getPlanRoot(),
                        rightFragment.getPlanRoot(),
                        null);

                joinNode.setLimit(node.getLimit());
                joinNode.computeStatistics(optExpr.getStatistics());
                List<Expr> conjuncts = Utils.extractConjuncts(node.getPredicate()).stream()
                        .map(e -> ScalarOperatorToExpr.buildExecExpression(e,
                                new ScalarOperatorToExpr.FormatterContext(context.getColRefToExpr())))
                        .collect(Collectors.toList());
                joinNode.addConjuncts(conjuncts);
                List<Expr> onConjuncts = Utils.extractConjuncts(node.getOnPredicate()).stream()
                        .map(e -> ScalarOperatorToExpr.buildExecExpression(e,
                                new ScalarOperatorToExpr.FormatterContext(context.getColRefToExpr())))
                        .collect(Collectors.toList());
                joinNode.addConjuncts(onConjuncts);
                // Connect parent and child fragment
                rightFragment.getPlanRoot().setFragment(leftFragment);

                // Currently, we always generate new fragment for PhysicalDistribution.
                // So we need to remove exchange node only fragment for Join.
                context.getFragments().remove(rightFragment);

                // Move leftFragment to end, it depends on all of its children
                context.getFragments().remove(leftFragment);
                context.getFragments().add(leftFragment);

                leftFragment.setPlanRoot(joinNode);
                if (!rightFragment.getChildren().isEmpty()) {
                    // right table isn't value operator
                    leftFragment.addChild(rightFragment.getChild(0));
                }

                if (!(joinNode.getChild(1) instanceof ExchangeNode)) {
                    joinNode.setReplicated(true);
                }

                leftFragment.mergeQueryGlobalDicts(rightFragment.getQueryGlobalDicts());
                return leftFragment;
            } else {
                JoinOperator joinOperator = node.getJoinType();

                PlanNode leftFragmentPlanRoot = leftFragment.getPlanRoot();
                PlanNode rightFragmentPlanRoot = rightFragment.getPlanRoot();
                // skip decode node
                if (leftFragmentPlanRoot instanceof DecodeNode) {
                    leftFragmentPlanRoot = leftFragmentPlanRoot.getChild(0);
                }
                if (rightFragmentPlanRoot instanceof DecodeNode) {
                    rightFragmentPlanRoot = rightFragmentPlanRoot.getChild(0);
                }
                // 1. Get distributionMode
                HashJoinNode.DistributionMode distributionMode;
                if (leftFragmentPlanRoot instanceof ExchangeNode &&
                        ((ExchangeNode) leftFragmentPlanRoot).getDistributionType()
                                .equals(DistributionSpec.DistributionType.SHUFFLE) &&
                        rightFragmentPlanRoot instanceof ExchangeNode &&
                        ((ExchangeNode) rightFragmentPlanRoot).getDistributionType()
                                .equals(DistributionSpec.DistributionType.SHUFFLE)) {
                    distributionMode = HashJoinNode.DistributionMode.PARTITIONED;
                } else if (rightFragmentPlanRoot instanceof ExchangeNode &&
                        ((ExchangeNode) rightFragmentPlanRoot).getDistributionType()
                                .equals(DistributionSpec.DistributionType.BROADCAST)) {
                    distributionMode = HashJoinNode.DistributionMode.BROADCAST;
                } else if (!(leftFragmentPlanRoot instanceof ExchangeNode) &&
                        !(rightFragmentPlanRoot instanceof ExchangeNode)) {
                    if (isColocateJoin(optExpr, context, leftFragmentPlanRoot, rightFragmentPlanRoot)) {
                        distributionMode = HashJoinNode.DistributionMode.COLOCATE;
                    } else if (ConnectContext.get().getSessionVariable().isEnableReplicationJoin() &&
                            rightFragmentPlanRoot.canDoReplicatedJoin()) {
                        distributionMode = HashJoinNode.DistributionMode.REPLICATED;
                    } else if (isShuffleJoin(optExpr)) {
                        distributionMode = HashJoinNode.DistributionMode.SHUFFLE_HASH_BUCKET;
                    } else {
                        Preconditions.checkState(false, "Must be replicate join or colocate join");
                        distributionMode = HashJoinNode.DistributionMode.COLOCATE;
                    }
                } else if (isShuffleJoin(optExpr)) {
                    distributionMode = HashJoinNode.DistributionMode.SHUFFLE_HASH_BUCKET;
                } else {
                    distributionMode = HashJoinNode.DistributionMode.LOCAL_HASH_BUCKET;
                }

                for (BinaryPredicateOperator s : eqOnPredicates) {
                    if (!optExpr.inputAt(0).getLogicalProperty().getOutputColumns()
                            .containsAll(s.getChild(0).getUsedColumns())) {
                        s.swap();
                    }
                }

                List<Expr> eqJoinConjuncts =
                        eqOnPredicates.stream().map(e -> ScalarOperatorToExpr.buildExecExpression(e,
                                        new ScalarOperatorToExpr.FormatterContext(context.getColRefToExpr())))
                                .collect(Collectors.toList());

                for (Expr expr : eqJoinConjuncts) {
                    if (expr.isConstant()) {
                        throw unsupportedException("Support join on constant predicate later");
                    }
                }

                List<ScalarOperator> otherJoin = Utils.extractConjuncts(node.getOnPredicate());
                otherJoin.removeAll(eqOnPredicates);
                List<Expr> otherJoinConjuncts = otherJoin.stream().map(e -> ScalarOperatorToExpr.buildExecExpression(e,
                                new ScalarOperatorToExpr.FormatterContext(context.getColRefToExpr())))
                        .collect(Collectors.toList());

                // 3. Get conjuncts
                List<ScalarOperator> predicates = Utils.extractConjuncts(node.getPredicate());
                List<Expr> conjuncts = predicates.stream().map(e -> ScalarOperatorToExpr.buildExecExpression(e,
                                new ScalarOperatorToExpr.FormatterContext(context.getColRefToExpr())))
                        .collect(Collectors.toList());

                if (joinOperator.isLeftOuterJoin()) {
                    for (TupleId tupleId : rightFragment.getPlanRoot().getTupleIds()) {
                        context.getDescTbl().getTupleDesc(tupleId).getSlots().forEach(slot -> slot.setIsNullable(true));
                    }
                } else if (joinOperator.isRightOuterJoin()) {
                    for (TupleId tupleId : leftFragment.getPlanRoot().getTupleIds()) {
                        context.getDescTbl().getTupleDesc(tupleId).getSlots().forEach(slot -> slot.setIsNullable(true));
                    }
                } else if (joinOperator.isFullOuterJoin()) {
                    for (TupleId tupleId : leftFragment.getPlanRoot().getTupleIds()) {
                        context.getDescTbl().getTupleDesc(tupleId).getSlots().forEach(slot -> slot.setIsNullable(true));
                    }
                    for (TupleId tupleId : rightFragment.getPlanRoot().getTupleIds()) {
                        context.getDescTbl().getTupleDesc(tupleId).getSlots().forEach(slot -> slot.setIsNullable(true));
                    }
                }

                HashJoinNode hashJoinNode = new HashJoinNode(
                        context.getNextNodeId(),
                        leftFragment.getPlanRoot(), rightFragment.getPlanRoot(),
                        joinOperator, eqJoinConjuncts, otherJoinConjuncts);

                //Build outputColumns
                if (node.getProjection() != null) {
                    ColumnRefSet outputColumns = new ColumnRefSet();
                    for (ScalarOperator s : node.getProjection().getColumnRefMap().values()) {
                        outputColumns.union(s.getUsedColumns());
                    }
                    for (ScalarOperator s : node.getProjection().getCommonSubOperatorMap().values()) {
                        outputColumns.union(s.getUsedColumns());
                    }

                    outputColumns.except(new ArrayList<>(node.getProjection().getCommonSubOperatorMap().keySet()));
                    hashJoinNode.setOutputSlots(
                            outputColumns.getStream().boxed().collect(Collectors.toList()));
                }

                hashJoinNode.setDistributionMode(distributionMode);
                hashJoinNode.getConjuncts().addAll(conjuncts);
                hashJoinNode.setLimit(node.getLimit());
                hashJoinNode.computeStatistics(optExpr.getStatistics());

                // when enable_pipeline_engine=true and enable_global_runtime_filter=false, global runtime filter
                // also needs be planned, because in pipeline engine, operators need local_rf_waiting_set constructed
                // from global runtime filters to determine local runtime filters generated by which HashJoinNode
                // to be waited to be completed. in this scenario, global runtime filters are built just for
                // obtaining local_rf_waiting_set, so they are cleaned before deliver fragment instances to BEs.
                boolean shouldBuildGlobalRuntimeFilter = ConnectContext.get() != null &&
                        (ConnectContext.get().getSessionVariable().getEnableGlobalRuntimeFilter() ||
                                ConnectContext.get().getSessionVariable().isEnablePipelineEngine());
                if (shouldBuildGlobalRuntimeFilter) {
                    hashJoinNode.buildRuntimeFilters(runtimeFilterIdIdGenerator, hashJoinNode.getChild(1),
                            hashJoinNode.getEqJoinConjuncts(), joinOperator);
                }

                if (distributionMode.equals(HashJoinNode.DistributionMode.BROADCAST)) {
                    setJoinPushDown(hashJoinNode);

                    // Connect parent and child fragment
                    rightFragment.getPlanRoot().setFragment(leftFragment);

                    // Currently, we always generate new fragment for PhysicalDistribution.
                    // So we need to remove exchange node only fragment for Join.
                    context.getFragments().remove(rightFragment);

                    // Move leftFragment to end, it depends on all of its children
                    context.getFragments().remove(leftFragment);
                    context.getFragments().add(leftFragment);
                    leftFragment.setPlanRoot(hashJoinNode);
                    leftFragment.addChild(rightFragment.getChild(0));
                    leftFragment.mergeQueryGlobalDicts(rightFragment.getQueryGlobalDicts());
                    estimateDopOfBroadcastJoinInPipeline(leftFragment);
                    return leftFragment;
                } else if (distributionMode.equals(HashJoinNode.DistributionMode.PARTITIONED)) {
                    DataPartition lhsJoinPartition = new DataPartition(TPartitionType.HASH_PARTITIONED,
                            leftFragment.getDataPartition().getPartitionExprs());
                    DataPartition rhsJoinPartition = new DataPartition(TPartitionType.HASH_PARTITIONED,
                            rightFragment.getDataPartition().getPartitionExprs());

                    leftFragment.getChild(0).setOutputPartition(lhsJoinPartition);
                    rightFragment.getChild(0).setOutputPartition(rhsJoinPartition);

                    // Currently, we always generate new fragment for PhysicalDistribution.
                    // So we need to remove exchange node only fragment for Join.
                    context.getFragments().remove(leftFragment);
                    context.getFragments().remove(rightFragment);

                    PlanFragment joinFragment = new PlanFragment(context.getNextFragmentId(),
                            hashJoinNode, lhsJoinPartition);
                    joinFragment.addChild(leftFragment.getChild(0));
                    joinFragment.addChild(rightFragment.getChild(0));

                    joinFragment.mergeQueryGlobalDicts(leftFragment.getQueryGlobalDicts());
                    joinFragment.mergeQueryGlobalDicts(rightFragment.getQueryGlobalDicts());
                    context.getFragments().add(joinFragment);

                    return joinFragment;
                } else if (distributionMode.equals(HashJoinNode.DistributionMode.COLOCATE) ||
                        distributionMode.equals(HashJoinNode.DistributionMode.REPLICATED)) {
                    if (distributionMode.equals(HashJoinNode.DistributionMode.COLOCATE)) {
                        hashJoinNode.setColocate(true, "");
                    } else {
                        hashJoinNode.setReplicated(true);
                    }
                    setJoinPushDown(hashJoinNode);

                    hashJoinNode.setChild(0, leftFragment.getPlanRoot());
                    hashJoinNode.setChild(1, rightFragment.getPlanRoot());
                    leftFragment.setPlanRoot(hashJoinNode);
                    context.getFragments().remove(rightFragment);

                    context.getFragments().remove(leftFragment);
                    context.getFragments().add(leftFragment);

                    leftFragment.mergeQueryGlobalDicts(rightFragment.getQueryGlobalDicts());
                    estimateDopOfBroadcastJoinInPipeline(leftFragment);
                    return leftFragment;
                } else if (distributionMode.equals(HashJoinNode.DistributionMode.SHUFFLE_HASH_BUCKET)) {
                    setJoinPushDown(hashJoinNode);

                    // distributionMode is SHUFFLE_HASH_BUCKET
                    if (!(leftFragment.getPlanRoot() instanceof ExchangeNode) &&
                            !(rightFragment.getPlanRoot() instanceof ExchangeNode)) {
                        hashJoinNode.setChild(0, leftFragment.getPlanRoot());
                        hashJoinNode.setChild(1, rightFragment.getPlanRoot());
                        leftFragment.setPlanRoot(hashJoinNode);
                        context.getFragments().remove(rightFragment);

                        context.getFragments().remove(leftFragment);
                        context.getFragments().add(leftFragment);

                        leftFragment.mergeQueryGlobalDicts(rightFragment.getQueryGlobalDicts());
                        return leftFragment;
                    } else if (leftFragment.getPlanRoot() instanceof ExchangeNode &&
                            !(rightFragment.getPlanRoot() instanceof ExchangeNode)) {
                        return computeShuffleHashBucketPlanFragment(context, rightFragment,
                                leftFragment, hashJoinNode);
                    } else {
                        return computeShuffleHashBucketPlanFragment(context, leftFragment,
                                rightFragment, hashJoinNode);
                    }
                } else {
                    setJoinPushDown(hashJoinNode);

                    // distributionMode is BUCKET_SHUFFLE
                    if (leftFragment.getPlanRoot() instanceof ExchangeNode &&
                            !(rightFragment.getPlanRoot() instanceof ExchangeNode)) {
                        return computeBucketShufflePlanFragment(context, rightFragment,
                                leftFragment, hashJoinNode);
                    } else {
                        return computeBucketShufflePlanFragment(context, leftFragment,
                                rightFragment, hashJoinNode);
                    }
                }
            }
        }

        private void collectOlapScanInFragment(OptExpression optExpression,
                                               List<PhysicalOlapScanOperator> scanNodeList) {
            Operator operator = optExpression.getOp();
            if (operator instanceof PhysicalOlapScanOperator) {
                scanNodeList.add((PhysicalOlapScanOperator) operator);
                return;
            }
            if (operator instanceof PhysicalDistributionOperator) {
                return;
            }
            for (OptExpression child : optExpression.getInputs()) {
                collectOlapScanInFragment(child, scanNodeList);
            }
        }

        private boolean isColocateJoin(OptExpression optExpression, ExecPlan context, PlanNode left, PlanNode right) {
            List<PhysicalOlapScanOperator> rightScanNodes = Lists.newArrayList();
            collectOlapScanInFragment(optExpression.inputAt(1), rightScanNodes);

            PhysicalHashJoinOperator joinNode = (PhysicalHashJoinOperator) optExpression.getOp();
            List<PhysicalOlapScanOperator> leftScanNodes = Lists.newArrayList();
            collectOlapScanInFragment(optExpression.inputAt(0), leftScanNodes);

            ColumnRefSet leftChildColumns = optExpression.getInputs().get(0).getOutputColumns();
            ColumnRefSet rightChildColumns = optExpression.getInputs().get(1).getOutputColumns();
            List<BinaryPredicateOperator> equalOnPredicate =
                    JoinPredicateUtils.getEqConj(leftChildColumns, rightChildColumns,
                            Utils.extractConjuncts(joinNode.getOnPredicate()));

            List<Integer> leftOnPredicateColumns = new ArrayList<>();
            List<Integer> rightOnPredicateColumns = new ArrayList<>();
            JoinPredicateUtils.getJoinOnPredicatesColumns(equalOnPredicate, leftChildColumns, rightChildColumns,
                    leftOnPredicateColumns, rightOnPredicateColumns);

            boolean leftChildSatisfied = leftScanNodes.stream().anyMatch(olapScanNode -> leftOnPredicateColumns
                    .containsAll(olapScanNode.getDistributionSpec().getHashDistributionDesc().getColumns()));

            boolean rightChildSatisfied = rightScanNodes.stream().anyMatch(olapScanNode -> rightOnPredicateColumns
                    .containsAll(olapScanNode.getDistributionSpec().getHashDistributionDesc().getColumns()));
            if (!leftChildSatisfied || !rightChildSatisfied) {
                return false;
            }

            ColocateTableIndex colocateIndex = Catalog.getCurrentColocateIndex();
            for (PhysicalOlapScanOperator node : leftScanNodes) {
                List<Integer> outputColumns =
                        node.getOutputColumns().stream().map(ColumnRefOperator::getId).collect(Collectors.toList());
                if (outputColumns.containsAll(leftOnPredicateColumns)) {
                    boolean isColocateGroup = colocateIndex
                            .isSameGroup(node.getTable().getId(), rightScanNodes.get(0).getTable().getId());
                    if (node.getTable().getId() == rightScanNodes.get(0).getTable().getId() &&
                            !isColocateGroup) {
                        return true;
                    } else {
                        return isColocateGroup &&
                                !colocateIndex.isGroupUnstable(colocateIndex.getGroup(node.getTable().getId()));
                    }
                }
            }
            return false;
        }

        public boolean isShuffleJoin(OptExpression optExpression) {
            // through the required properties type check if it is shuffle join
            return optExpression.getRequiredProperties().stream().allMatch(
                    physicalPropertySet -> {
                        if (!physicalPropertySet.getDistributionProperty().isShuffle()) {
                            return false;
                        }
                        HashDistributionDesc.SourceType hashSourceType =
                                ((HashDistributionSpec) (physicalPropertySet.getDistributionProperty().getSpec()))
                                        .getHashDistributionDesc().getSourceType();
                        if (hashSourceType.equals(HashDistributionDesc.SourceType.SHUFFLE_JOIN) ||
                                hashSourceType.equals(HashDistributionDesc.SourceType.SHUFFLE_ENFORCE)) {
                            return true;
                        }
                        return false;
                    });
        }

        public PlanFragment computeBucketShufflePlanFragment(ExecPlan context,
                                                             PlanFragment stayFragment,
                                                             PlanFragment removeFragment, HashJoinNode hashJoinNode) {
            hashJoinNode.setLocalHashBucket(true);
            hashJoinNode.setPartitionExprs(removeFragment.getDataPartition().getPartitionExprs());
            removeFragment.getChild(0)
                    .setOutputPartition(new DataPartition(TPartitionType.BUCKET_SHUFFLE_HASH_PARTITIONED,
                            removeFragment.getDataPartition().getPartitionExprs()));

            // Currently, we always generate new fragment for PhysicalDistribution.
            // So we need to remove exchange node only fragment for Join.
            context.getFragments().remove(removeFragment);

            context.getFragments().remove(stayFragment);
            context.getFragments().add(stayFragment);

            stayFragment.setPlanRoot(hashJoinNode);
            stayFragment.addChild(removeFragment.getChild(0));
            stayFragment.mergeQueryGlobalDicts(removeFragment.getQueryGlobalDicts());
            return stayFragment;
        }

        public PlanFragment computeShuffleHashBucketPlanFragment(ExecPlan context,
                                                                 PlanFragment stayFragment,
                                                                 PlanFragment removeFragment,
                                                                 HashJoinNode hashJoinNode) {
            hashJoinNode.setPartitionExprs(removeFragment.getDataPartition().getPartitionExprs());
            removeFragment.getChild(0)
                    .setOutputPartition(new DataPartition(TPartitionType.HASH_PARTITIONED,
                            removeFragment.getDataPartition().getPartitionExprs()));

            // Currently, we always generate new fragment for PhysicalDistribution.
            // So we need to remove exchange node only fragment for Join.
            context.getFragments().remove(removeFragment);

            context.getFragments().remove(stayFragment);
            context.getFragments().add(stayFragment);

            stayFragment.setPlanRoot(hashJoinNode);
            stayFragment.addChild(removeFragment.getChild(0));
            stayFragment.mergeQueryGlobalDicts(removeFragment.getQueryGlobalDicts());
            return stayFragment;
        }

        @Override
        public PlanFragment visitPhysicalAssertOneRow(OptExpression optExpression, ExecPlan context) {
            PlanFragment inputFragment = visit(optExpression.inputAt(0), context);

            // AssertNode will fill null row if child result is empty, should create new tuple use null type column
            for (TupleId id : inputFragment.getPlanRoot().getTupleIds()) {
                context.getDescTbl().getTupleDesc(id).getSlots().forEach(s -> s.setIsNullable(true));
            }

            PhysicalAssertOneRowOperator assertOneRow = (PhysicalAssertOneRowOperator) optExpression.getOp();
            AssertNumRowsNode node =
                    new AssertNumRowsNode(context.getNextNodeId(), inputFragment.getPlanRoot(),
                            new AssertNumRowsElement(assertOneRow.getCheckRows(), assertOneRow.getTips(),
                                    assertOneRow.getAssertion()));
            node.computeStatistics(optExpression.getStatistics());
            inputFragment.setPlanRoot(node);
            return inputFragment;
        }

        @Override
        public PlanFragment visitPhysicalAnalytic(OptExpression optExpr, ExecPlan context) {
            PlanFragment inputFragment = visit(optExpr.inputAt(0), context);
            PhysicalWindowOperator node = (PhysicalWindowOperator) optExpr.getOp();

            List<Expr> analyticFnCalls = new ArrayList<>();
            TupleDescriptor outputTupleDesc = context.getDescTbl().createTupleDescriptor();
            for (Map.Entry<ColumnRefOperator, CallOperator> analyticCall : node.getAnalyticCall().entrySet()) {
                Expr analyticFunction = ScalarOperatorToExpr.buildExecExpression(analyticCall.getValue(),
                        new ScalarOperatorToExpr.FormatterContext(context.getColRefToExpr()));
                analyticFnCalls.add(analyticFunction);

                SlotDescriptor slotDesc = context.getDescTbl()
                        .addSlotDescriptor(outputTupleDesc, new SlotId(analyticCall.getKey().getId()));
                slotDesc.setType(analyticFunction.getType());
                slotDesc.setIsNullable(analyticFunction.isNullable());
                slotDesc.setIsMaterialized(true);
                context.getColRefToExpr()
                        .put(analyticCall.getKey(), new SlotRef(analyticCall.getKey().toString(), slotDesc));
            }

            List<Expr> partitionExprs =
                    node.getPartitionExpressions().stream().map(e -> ScalarOperatorToExpr.buildExecExpression(e,
                                    new ScalarOperatorToExpr.FormatterContext(context.getColRefToExpr())))
                            .collect(Collectors.toList());

            List<OrderByElement> orderByElements = node.getOrderByElements().stream().map(e -> new OrderByElement(
                    ScalarOperatorToExpr.buildExecExpression(e.getColumnRef(),
                            new ScalarOperatorToExpr.FormatterContext(context.getColRefToExpr())),
                    e.isAscending(), e.isNullsFirst())).collect(Collectors.toList());

            AnalyticEvalNode analyticEvalNode = new AnalyticEvalNode(
                    context.getNextNodeId(),
                    inputFragment.getPlanRoot(),
                    analyticFnCalls,
                    partitionExprs,
                    orderByElements,
                    node.getAnalyticWindow(),
                    null, outputTupleDesc,
                    null, null, null,
                    context.getDescTbl().createTupleDescriptor());
            analyticEvalNode.setSubstitutedPartitionExprs(partitionExprs);
            analyticEvalNode.setLimit(node.getLimit());
            analyticEvalNode.setHasNullableGenerateChild();
            analyticEvalNode.computeStatistics(optExpr.getStatistics());

            // set predicate
            List<ScalarOperator> predicates = Utils.extractConjuncts(node.getPredicate());
            ScalarOperatorToExpr.FormatterContext formatterContext =
                    new ScalarOperatorToExpr.FormatterContext(context.getColRefToExpr());
            for (ScalarOperator predicate : predicates) {
                analyticEvalNode.getConjuncts()
                        .add(ScalarOperatorToExpr.buildExecExpression(predicate, formatterContext));
            }
            // In new planner
            // Add partition exprs of AnalyticEvalNode to SortNode, it is used in pipeline execution engine
            // to eliminate time-consuming LocalMergeSortSourceOperator and parallelize AnalyticNode.
            PlanNode root = inputFragment.getPlanRoot();
            if (root instanceof SortNode) {
                SortNode sortNode = (SortNode) root;
                sortNode.setAnalyticPartitionExprs(analyticEvalNode.getPartitionExprs());
            }

            inputFragment.setPlanRoot(analyticEvalNode);
            return inputFragment;
        }

        private PlanFragment buildSetOperation(OptExpression optExpr, ExecPlan context, OperatorType operatorType) {
            PhysicalSetOperation setOperation = (PhysicalSetOperation) optExpr.getOp();
            TupleDescriptor setOperationTuple = context.getDescTbl().createTupleDescriptor();

            for (ColumnRefOperator columnRefOperator : setOperation.getOutputColumnRefOp()) {
                SlotDescriptor slotDesc = context.getDescTbl()
                        .addSlotDescriptor(setOperationTuple, new SlotId(columnRefOperator.getId()));
                slotDesc.setType(columnRefOperator.getType());
                slotDesc.setIsMaterialized(true);
                slotDesc.setIsNullable(columnRefOperator.isNullable());

                context.getColRefToExpr().put(columnRefOperator, new SlotRef(columnRefOperator.toString(), slotDesc));
            }

            SetOperationNode setOperationNode;
            boolean isUnionAll = false;
            if (operatorType.equals(OperatorType.PHYSICAL_UNION)) {
                setOperationNode = new UnionNode(context.getNextNodeId(), setOperationTuple.getId());
                isUnionAll = ((PhysicalUnionOperator) setOperation).isUnionAll();
                setOperationNode.setFirstMaterializedChildIdx_(optExpr.arity());
            } else if (operatorType.equals(OperatorType.PHYSICAL_EXCEPT)) {
                setOperationNode = new ExceptNode(context.getNextNodeId(), setOperationTuple.getId());
            } else if (operatorType.equals(OperatorType.PHYSICAL_INTERSECT)) {
                setOperationNode = new IntersectNode(context.getNextNodeId(), setOperationTuple.getId());
            } else {
                throw new StarRocksPlannerException("Unsupported set operation", INTERNAL_ERROR);
            }

            List<Map<Integer, Integer>> outputSlotIdToChildSlotIdMaps = new ArrayList<>();
            for (int childIdx = 0; childIdx < optExpr.arity(); ++childIdx) {
                Map<Integer, Integer> slotIdMap = new HashMap<>();
                List<ColumnRefOperator> childOutput = setOperation.getChildOutputColumns().get(childIdx);
                Preconditions.checkState(childOutput.size() == setOperation.getOutputColumnRefOp().size());
                for (int columnIdx = 0; columnIdx < setOperation.getOutputColumnRefOp().size(); ++columnIdx) {
                    Integer resultColumnIdx = setOperation.getOutputColumnRefOp().get(columnIdx).getId();
                    slotIdMap.put(resultColumnIdx, childOutput.get(columnIdx).getId());
                }
                outputSlotIdToChildSlotIdMaps.add(slotIdMap);
                Preconditions.checkState(slotIdMap.size() == setOperation.getOutputColumnRefOp().size());
            }
            setOperationNode.setOutputSlotIdToChildSlotIdMaps(outputSlotIdToChildSlotIdMaps);

            Preconditions.checkState(optExpr.getInputs().size() == setOperation.getChildOutputColumns().size());

            PlanFragment setOperationFragment =
                    new PlanFragment(context.getNextFragmentId(), setOperationNode, DataPartition.RANDOM);
            List<List<Expr>> materializedResultExprLists = Lists.newArrayList();

            for (int i = 0; i < optExpr.getInputs().size(); i++) {
                List<ColumnRefOperator> childOutput = setOperation.getChildOutputColumns().get(i);
                PlanFragment fragment = visit(optExpr.getInputs().get(i), context);

                List<Expr> materializedExpressions = Lists.newArrayList();

                // keep output column order
                for (ColumnRefOperator ref : childOutput) {
                    SlotDescriptor slotDescriptor = context.getDescTbl().getSlotDesc(new SlotId(ref.getId()));
                    materializedExpressions.add(new SlotRef(slotDescriptor));
                }

                materializedResultExprLists.add(materializedExpressions);

                if (isUnionAll) {
                    fragment.setOutputPartition(DataPartition.RANDOM);
                } else {
                    fragment.setOutputPartition(DataPartition.hashPartitioned(materializedExpressions));
                }

                // nothing distribute can satisfy set-operator, must shuffle data
                ExchangeNode exchangeNode = new ExchangeNode(context.getNextNodeId(),
                        fragment.getPlanRoot(), false);

                exchangeNode.setFragment(setOperationFragment);
                fragment.setDestination(exchangeNode);
                setOperationNode.addChild(exchangeNode);
            }

            // reset column is nullable, for handle union select xx join select xxx...
            setOperationNode.setHasNullableGenerateChild();
            for (ColumnRefOperator columnRefOperator : setOperation.getOutputColumnRefOp()) {
                SlotDescriptor slotDesc = context.getDescTbl().getSlotDesc(new SlotId(columnRefOperator.getId()));
                slotDesc.setIsNullable(slotDesc.getIsNullable() | setOperationNode.isHasNullableGenerateChild());
            }
            setOperationTuple.computeMemLayout();

            setOperationNode.setMaterializedResultExprLists_(materializedResultExprLists);
            setOperationNode.setLimit(setOperation.getLimit());
            setOperationNode.computeStatistics(optExpr.getStatistics());

            context.getFragments().add(setOperationFragment);
            return setOperationFragment;
        }

        @Override
        public PlanFragment visitPhysicalUnion(OptExpression optExpr, ExecPlan context) {
            return buildSetOperation(optExpr, context, OperatorType.PHYSICAL_UNION);
        }

        @Override
        public PlanFragment visitPhysicalExcept(OptExpression optExpr, ExecPlan context) {
            return buildSetOperation(optExpr, context, OperatorType.PHYSICAL_EXCEPT);
        }

        @Override
        public PlanFragment visitPhysicalIntersect(OptExpression optExpr, ExecPlan context) {
            return buildSetOperation(optExpr, context, OperatorType.PHYSICAL_INTERSECT);
        }

        @Override
        public PlanFragment visitPhysicalRepeat(OptExpression optExpr, ExecPlan context) {
            PlanFragment inputFragment = visit(optExpr.inputAt(0), context);
            PhysicalRepeatOperator repeatOperator = (PhysicalRepeatOperator) optExpr.getOp();

            TupleDescriptor outputGroupingTuple = context.getDescTbl().createTupleDescriptor();
            for (ColumnRefOperator columnRefOperator : repeatOperator.getOutputGrouping()) {
                SlotDescriptor slotDesc = context.getDescTbl()
                        .addSlotDescriptor(outputGroupingTuple, new SlotId(columnRefOperator.getId()));
                slotDesc.setType(columnRefOperator.getType());
                slotDesc.setIsMaterialized(true);
                slotDesc.setIsNullable(columnRefOperator.isNullable());

                context.getColRefToExpr().put(columnRefOperator, new SlotRef(columnRefOperator.toString(), slotDesc));
            }
            outputGroupingTuple.computeMemLayout();

            //RepeatSlotIdList
            List<Set<Integer>> repeatSlotIdList = new ArrayList<>();
            for (List<ColumnRefOperator> repeat : repeatOperator.getRepeatColumnRef()) {
                repeatSlotIdList.add(
                        repeat.stream().map(ColumnRefOperator::getId).collect(Collectors.toSet()));
            }

            RepeatNode repeatNode = new RepeatNode(
                    context.getNextNodeId(),
                    inputFragment.getPlanRoot(),
                    outputGroupingTuple,
                    repeatSlotIdList,
                    repeatOperator.getGroupingIds());
            List<ScalarOperator> predicates = Utils.extractConjuncts(repeatOperator.getPredicate());
            ScalarOperatorToExpr.FormatterContext formatterContext =
                    new ScalarOperatorToExpr.FormatterContext(context.getColRefToExpr());

            for (ScalarOperator predicate : predicates) {
                repeatNode.getConjuncts().add(ScalarOperatorToExpr.buildExecExpression(predicate, formatterContext));
            }
            repeatNode.computeStatistics(optExpr.getStatistics());

            inputFragment.setPlanRoot(repeatNode);
            return inputFragment;
        }

        @Override
        public PlanFragment visitPhysicalFilter(OptExpression optExpr, ExecPlan context) {
            PlanFragment inputFragment = visit(optExpr.inputAt(0), context);
            PhysicalFilterOperator filter = (PhysicalFilterOperator) optExpr.getOp();

            List<Expr> predicates = Utils.extractConjuncts(filter.getPredicate()).stream()
                    .map(d -> ScalarOperatorToExpr.buildExecExpression(d,
                            new ScalarOperatorToExpr.FormatterContext(context.getColRefToExpr())))
                    .collect(Collectors.toList());

            SelectNode selectNode =
                    new SelectNode(context.getNextNodeId(), inputFragment.getPlanRoot(), predicates);
            selectNode.setLimit(filter.getLimit());
            selectNode.computeStatistics(optExpr.getStatistics());
            inputFragment.setPlanRoot(selectNode);
            return inputFragment;
        }

        @Override
        public PlanFragment visitPhysicalTableFunction(OptExpression optExpression, ExecPlan context) {
            PlanFragment inputFragment = visit(optExpression.inputAt(0), context);
            PhysicalTableFunctionOperator physicalTableFunction = (PhysicalTableFunctionOperator) optExpression.getOp();

            TupleDescriptor udtfOutputTuple = context.getDescTbl().createTupleDescriptor();
            for (int columnId : physicalTableFunction.getOutputColumns().getColumnIds()) {
                ColumnRefOperator columnRefOperator = columnRefFactory.getColumnRef(columnId);

                SlotDescriptor slotDesc =
                        context.getDescTbl().addSlotDescriptor(udtfOutputTuple, new SlotId(columnRefOperator.getId()));
                slotDesc.setType(columnRefOperator.getType());
                slotDesc.setIsMaterialized(true);
                slotDesc.setIsNullable(columnRefOperator.isNullable());

                context.getColRefToExpr().put(columnRefOperator, new SlotRef(columnRefOperator.toString(), slotDesc));
            }
            udtfOutputTuple.computeMemLayout();

            TableFunctionNode tableFunctionNode = new TableFunctionNode(context.getNextNodeId(),
                    inputFragment.getPlanRoot(),
                    udtfOutputTuple,
                    physicalTableFunction.getFn(),
                    Arrays.stream(physicalTableFunction.getParamColumnRefs().getColumnIds()).boxed()
                            .collect(Collectors.toList()),
                    Arrays.stream(physicalTableFunction.getOuterColumnRefSet().getColumnIds()).boxed()
                            .collect(Collectors.toList()),
                    Arrays.stream(physicalTableFunction.getFnResultColumnRefSet().getColumnIds()).boxed()
                            .collect(Collectors.toList()));
            tableFunctionNode.setLimit(physicalTableFunction.getLimit());
            inputFragment.setPlanRoot(tableFunctionNode);
            return inputFragment;
        }

        @Override
        public PlanFragment visitPhysicalLimit(OptExpression optExpression, ExecPlan context) {
            // PhysicalLimit use for enforce gather property, Enforcer will produce more PhysicalDistribution, there
            // don't need do anything.
            return visit(optExpression.inputAt(0), context);
        }

        @Override
        public PlanFragment visitPhysicalCTEConsume(OptExpression optExpression, ExecPlan context) {
            PhysicalCTEConsumeOperator consume = (PhysicalCTEConsumeOperator) optExpression.getOp();
            int cteId = consume.getCteId();

            MultiCastPlanFragment cteFragment = (MultiCastPlanFragment) context.getCteProduceFragments().get(cteId);

            ExchangeNode exchangeNode = new ExchangeNode(context.getNextNodeId(),
                    cteFragment.getPlanRoot(), false, DistributionSpec.DistributionType.SHUFFLE);
            exchangeNode.setNumInstances(cteFragment.getPlanRoot().getNumInstances());

            PlanFragment consumeFragment = new PlanFragment(context.getNextFragmentId(), exchangeNode,
                    cteFragment.getDataPartition());

            Map<ColumnRefOperator, ScalarOperator> projectMap = Maps.newHashMap();
            consume.getCteOutputColumnRefMap().forEach(projectMap::put);
            consumeFragment = buildProjectNode(optExpression, new Projection(projectMap), consumeFragment, context);
            consumeFragment.setQueryGlobalDicts(cteFragment.getQueryGlobalDicts());
            consumeFragment.setLoadGlobalDicts(cteFragment.getLoadGlobalDicts());

            // add filter node
            if (consume.getPredicate() != null) {
                List<Expr> predicates = Utils.extractConjuncts(consume.getPredicate()).stream()
                        .map(d -> ScalarOperatorToExpr.buildExecExpression(d,
                                new ScalarOperatorToExpr.FormatterContext(context.getColRefToExpr())))
                        .collect(Collectors.toList());
                SelectNode selectNode =
                        new SelectNode(context.getNextNodeId(), consumeFragment.getPlanRoot(), predicates);
                selectNode.computeStatistics(optExpression.getStatistics());
                consumeFragment.setPlanRoot(selectNode);
            }

            // set limit
            if (consume.hasLimit()) {
                consumeFragment.getPlanRoot().setLimit(consume.getLimit());
            }

            cteFragment.getDestNodeList().add(exchangeNode);
            consumeFragment.addChild(cteFragment);
            context.getFragments().add(consumeFragment);
            return consumeFragment;
        }

        @Override
        public PlanFragment visitPhysicalCTEProduce(OptExpression optExpression, ExecPlan context) {
            PlanFragment child = visit(optExpression.inputAt(0), context);
            int cteId = ((PhysicalCTEProduceOperator) optExpression.getOp()).getCteId();
            context.getFragments().remove(child);
            MultiCastPlanFragment cteProduce = new MultiCastPlanFragment(child);

            List<Expr> outputs = Lists.newArrayList();
            optExpression.getOutputColumns().getStream()
                    .forEach(i -> outputs.add(context.getColRefToExpr().get(columnRefFactory.getColumnRef(i))));

            cteProduce.setOutputExprs(outputs);
            context.getCteProduceFragments().put(cteId, cteProduce);
            context.getFragments().add(cteProduce);
            return child;
        }

        @Override
        public PlanFragment visitPhysicalCTEAnchor(OptExpression optExpression, ExecPlan context) {
            visit(optExpression.inputAt(0), context);
            return visit(optExpression.inputAt(1), context);
        }

        @Override
        public PlanFragment visitPhysicalNoCTE(OptExpression optExpression, ExecPlan context) {
            return visit(optExpression.inputAt(0), context);
        }
    }
}
