/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 *   SPDX-License-Identifier: Apache-2.0
 */

package org.aksw.jena.proxy.algebra;

import java.util.List;
import java.util.Set;
import org.apache.jena.query.SortCondition;
import org.apache.jena.sparql.algebra.*;
import org.apache.jena.sparql.algebra.op.*;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.expr.ExprVars;

/**
 * Expose ordered unions by placing ORDER below compatible projections, stable filters and DISTINCT.
 * All DISTINCT nodes remain ordinary Jena operators, retaining global duplicate elimination. This
 * rule assumes DISTINCT preserves input order; callers must retain a fallback when their execution
 * engine cannot provide that contract (e.g. affected spill configurations). It does not move
 * ordering through joins, slices, LATERAL or opaque expressions.
 */
public class TransformOrderPlacement extends TransformCopy {
    /** Create the stateless placement transform. */
    public TransformOrderPlacement() {}

    @Override
    public Op transform(OpOrder order, Op child) {
        if (!TransformOrderUnion.supported(order.getConditions())) return super.transform(order, child);
        return place(child, order.getConditions());
    }

    @Override
    public Op transform(OpTopN top, Op child) {
        if (!TransformOrderUnion.supported(top.getConditions())) return super.transform(top, child);
        // The global bound follows DISTINCT. Applying TopN independently before
        // cross-branch duplicate elimination can return too few distinct results.
        Op placed = place(child, top.getConditions());
        return hasDistributionCandidate(placed)
                ? new OpSlice(placed, 0, top.getLimit())
                : super.transform(top, child);
    }

    private static boolean hasDistributionCandidate(Op op) {
        if (op instanceof OpOrder order) {
            Op input = order.getSubOp();
            if (input instanceof OpUnion || input instanceof OpDisjunction) return true;
            if (input instanceof OpService service)
                return service.getService().isURI() && !service.getSilent();
            Op normalized = input;
            if (input instanceof OpSequence sequence && sequence.size() == 2) {
                normalized = OpJoin.create(sequence.get(0), sequence.get(1));
            }
            normalized = TransformGroupTable.normalizeSingletonJoin(normalized);
            if (normalized instanceof OpExtend extend
                    && extend.getVarExprList().getExprs().values().stream().allMatch(e -> e.isConstant())) {
                return hasDistributionCandidate(new OpOrder(extend.getSubOp(), order.getConditions()));
            }
            return false;
        }
        return op instanceof Op1 unary && hasDistributionCandidate(unary.getSubOp());
    }

    private static Op place(Op input, List<SortCondition> conditions) {
        Set<Var> required = ExprVars.getVarsMentioned(conditions);
        if (input instanceof OpProject project && project.getVars().containsAll(required)) {
            return new OpProject(place(project.getSubOp(), conditions), project.getVars());
        }
        if (input instanceof OpDistinct distinct
                && required.stream().allMatch(var -> var.isNamedVar())
                && OpVars.visibleVars(distinct.getSubOp()).containsAll(required)) {
            return OpDistinct.create(place(distinct.getSubOp(), conditions));
        }
        if (input instanceof OpFilter filter
                && filter.getExprs().getList().stream().allMatch(ExprSafety::isStableAndTransparent)) {
            return OpFilter.filterBy(filter.getExprs(), place(filter.getSubOp(), conditions));
        }
        return new OpOrder(input, conditions);
    }
}
