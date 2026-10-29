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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import org.apache.jena.query.SortCondition;
import org.apache.jena.sparql.algebra.*;
import org.apache.jena.sparql.algebra.op.*;
import org.apache.jena.sparql.expr.Expr;
import org.apache.jena.sparql.expr.ExprVars;

/**
 * Push branch ordering into approved, fixed, non-SILENT services. The approval predicate also
 * certifies that endpoint ordering agrees with the local merge's RDF term comparison.
 * Only variable/constant keys are delegated. Constants defining graph aliases stay local;
 * independent singleton VALUES joins are recognized in both Join and Sequence form.
 */
public class TransformOrderService extends TransformCopy {
    private final Predicate<OpService> allowed;

    public TransformOrderService(Predicate<OpService> allowed) {
        this.allowed = java.util.Objects.requireNonNull(allowed);
    }

    @Override
    public Op transform(OpExt ext) {
        if (ext instanceof OpMergeSorted merge) {
            return new OpMergeSorted(merge.getBranches().stream().map(branch -> {
                if (branch instanceof OpOrder order) return push(order.getSubOp(), order.getConditions());
                return branch;
            }).toList(), merge.getConditions(), merge.effectiveOp());
        }
        return super.transform(ext);
    }

    @Override
    public Op transform(OpOrder order, Op subOp) {
        if (!TransformOrderUnion.supported(order.getConditions())) return super.transform(order, subOp);
        Op original = new OpOrder(subOp, order.getConditions());
        Op pushed = push(subOp, order.getConditions());
        return original.equals(pushed) ? super.transform(order, subOp)
                : new OpMergeSorted(List.of(pushed), order.getConditions(), original);
    }

    @Override
    public Op transform(OpTopN top, Op subOp) {
        Op order = transform(new OpOrder(subOp, top.getConditions()), subOp);
        return order instanceof OpMergeSorted ? new OpSlice(order, 0, top.getLimit()) : super.transform(top, subOp);
    }

    private Op push(Op input, List<SortCondition> conditions) {
        if (conditions.isEmpty()) return input;
        if (input instanceof OpService service && service.getService().isURI()
                && !service.getSilent() && allowed.test(service)) {
            return new OpService(service.getService(), new OpOrder(service.getSubOp(), conditions), false);
        }
        if (input instanceof OpProject project
                && project.getVars().containsAll(ExprVars.getVarsMentioned(conditions))) {
            return new OpProject(push(project.getSubOp(), conditions), project.getVars());
        }
        if (input instanceof OpFilter filter && filter.getExprs().getList().stream()
                .allMatch(ExprSafety::isStableAndTransparent)) {
            return OpFilter.filterBy(filter.getExprs(), push(filter.getSubOp(), conditions));
        }
        Op normalized = input;
        if (input instanceof OpSequence sequence && sequence.size() == 2) {
            normalized = OpJoin.create(sequence.get(0), sequence.get(1));
        }
        normalized = TransformGroupTable.normalizeSingletonJoin(normalized);
        if (normalized instanceof OpExtend extend && extend.getVarExprList().getExprs().values().stream()
                .allMatch(Expr::isConstant) && Collections.disjoint(extend.getVarExprList().getVars(),
                        OpVars.mentionedVars(extend.getSubOp()))
                && ExprSafety.hasOnlyStableTransparentExpressions(extend.getSubOp())) {
            List<SortCondition> remaining = new ArrayList<>();
            for (SortCondition condition : conditions) {
                Expr expr = condition.getExpression();
                if (expr.isConstant() || expr.isVariable()
                        && extend.getVarExprList().contains(expr.asVar())) continue;
                remaining.add(condition);
            }
            return OpExtend.create(push(extend.getSubOp(), remaining), extend.getVarExprList());
        }
        return new OpOrder(input, conditions);
    }
}
