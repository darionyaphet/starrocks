// This file is made available under Elastic License 2.0.
// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/main/java/org/apache/doris/analysis/Subquery.java

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.starrocks.analysis;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.starrocks.catalog.ArrayType;
import com.starrocks.catalog.StructField;
import com.starrocks.catalog.StructType;
import com.starrocks.common.AnalysisException;
import com.starrocks.common.UserException;
import com.starrocks.sql.analyzer.SemanticException;
import com.starrocks.sql.ast.AstVisitor;
import com.starrocks.sql.ast.QueryRelation;
import com.starrocks.thrift.TExprNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Class representing a subquery. A Subquery consists of a QueryStmt and has
 * its own Analyzer context.
 */
// Our new cost based query optimizer is more powerful and stable than old query optimizer,
// The old query optimizer related codes could be deleted safely.
// TODO: Remove old query optimizer related codes before 2021-09-30
public class Subquery extends Expr {
    private static final Logger LOG = LoggerFactory.getLogger(Subquery.class);

    // The QueryStmt of the subquery.
    protected QueryStmt stmt;
    // A subquery has its own analysis context
    protected Analyzer analyzer;

    public Analyzer getAnalyzer() {
        return analyzer;
    }

    public QueryStmt getStatement() {
        return stmt;
    }

    @Override
    public String toSqlImpl() {
        if (stmt != null) {
            return "(" + stmt.toSql() + ")";
        } else {
            return "(" + queryRelation.toSql() + ")";
        }
    }

    @Override
    public String toDigestImpl() {
        return "(" + stmt.toDigest() + ")";
    }

    /**
     * C'tor that initializes a Subquery from a QueryStmt.
     */
    public Subquery(QueryStmt queryStmt) {
        super();
        Preconditions.checkNotNull(queryStmt);
        stmt = queryStmt;
        stmt.setNeedToSql(true);
    }

    /**
     * Copy c'tor.
     */
    public Subquery(Subquery other) {
        super(other);
        stmt = other.stmt.clone();
        // needToSql will affect the toSQL result, so we must clone it
        stmt.setNeedToSql(other.stmt.needToSql);
        analyzer = other.analyzer;
    }


    public Subquery(QueryRelation queryBlock) {
        super();
        this.queryRelation = queryBlock;
    }

    /**
     * Analyzes the subquery in a child analyzer.
     */
    @Override
    public void analyzeImpl(Analyzer parentAnalyzer) throws AnalysisException {
        if (!(stmt instanceof SelectStmt)) {
            throw new AnalysisException("A subquery must contain a single select block: " +
                    toSql());
        }
        // The subquery is analyzed with its own analyzer.
        analyzer = new Analyzer(parentAnalyzer);
        analyzer.setIsSubquery();
        try {
            stmt.analyze(analyzer);
        } catch (UserException e) {
            throw new AnalysisException(e.getMessage());
        }
        // Check whether the stmt_ contains an illegal mix of un/correlated table refs.
        stmt.getCorrelatedTupleIds(analyzer);

        // Set the subquery type based on the types of the exprs in the
        // result list of the associated SelectStmt.
        ArrayList<Expr> stmtResultExprs = stmt.getResultExprs();
        if (stmtResultExprs.size() == 1) {
            type = stmtResultExprs.get(0).getType();
            Preconditions.checkState(!type.isComplexType());
        } else {
            type = createStructTypeFromExprList();
        }

        // If the subquery returns many rows, set its type to ArrayType.
        if (!((SelectStmt) stmt).returnsSingleRow()) {
            type = new ArrayType(type, true);
        }
    }

    @Override
    protected boolean isConstantImpl() {
        return false;
    }

    /**
     * Check if the subquery's SelectStmt returns a single column of scalar type.
     */
    public boolean returnsScalarColumn() {
        ArrayList<Expr> stmtResultExprs = stmt.getResultExprs();
        if (stmtResultExprs.size() == 1 && stmtResultExprs.get(0).getType().isScalarType()) {
            return true;
        }
        return false;
    }

    /**
     * Create a StrucType from the result expr list of a subquery's SelectStmt.
     */
    private StructType createStructTypeFromExprList() {
        List<Expr> stmtResultExprs = stmt.getResultExprs();
        ArrayList<StructField> structFields = Lists.newArrayList();
        // Check if we have unique labels
        List<String> labels = stmt.getColLabels();
        boolean hasUniqueLabels = true;
        if (Sets.newHashSet(labels).size() != labels.size()) {
            hasUniqueLabels = false;
        }

        // Construct a StructField from each expr in the select list
        for (int i = 0; i < stmtResultExprs.size(); ++i) {
            Expr expr = stmtResultExprs.get(i);
            String fieldName = null;
            // Check if the label meets the Metastore's requirements.
            // TODO(zc)
            // if (MetastoreShim.validateName(labels.get(i))) {
            if (false) {
                fieldName = labels.get(i);
                // Make sure the field names are unique.
                if (!hasUniqueLabels) {
                    fieldName = "_" + Integer.toString(i) + "_" + fieldName;
                }
            } else {
                // Use the expr ordinal to construct a StructField.
                fieldName = "_" + Integer.toString(i);
            }
            Preconditions.checkNotNull(fieldName);
            structFields.add(new StructField(fieldName, expr.getType(), null));
        }
        Preconditions.checkState(structFields.size() != 0);
        return new StructType(structFields);
    }

    @Override
    public boolean isCorrelatedPredicate(List<TupleId> tupleIdList) {
        List<TupleId> tupleIdFromSubquery = stmt.collectTupleIds();
        for (TupleId tupleId : tupleIdList) {
            if (tupleIdFromSubquery.contains(tupleId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true if the toSql() of the Subqueries is identical. May return false for
     * equivalent statements even due to minor syntactic differences like parenthesis.
     * TODO: Switch to a less restrictive implementation.
     */
    @Override
    public boolean equals(Object o) {
        if (!super.equals(o)) {
            return false;
        }

        if (queryRelation != null) {
            if (((Subquery) o).getQueryRelation() == null) {
                return false;
            } else {
                return o.equals(queryRelation);
            }
        }

        return stmt.toSql().equals(((Subquery) o).stmt.toSql());
    }

    @Override
    public Subquery clone() {
        Subquery ret = new Subquery(this);
        LOG.debug("SUBQUERY clone old={} new={}",
                System.identityHashCode(this),
                System.identityHashCode(ret));
        return ret;
    }

    @Override
    protected void toThrift(TExprNode msg) {
    }

    /**
     * Below function is added by new analyzer
     */
    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) throws SemanticException {
        return visitor.visitSubquery(this, context);
    }

    /**
     * Analyzed subquery is store as QueryBlock
     */
    private QueryRelation queryRelation;

    public void setQueryRelation(QueryRelation queryRelation) {
        this.queryRelation = queryRelation;
    }

    public QueryRelation getQueryRelation() {
        return queryRelation;
    }
}