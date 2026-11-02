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

import java.util.*;
import org.apache.jena.sparql.algebra.*;
import org.apache.jena.sparql.algebra.op.*;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.expr.ExprVars;

/**
 * Push live columns toward union branches, retaining hidden sort/filter dependencies and a final
 * client projection. DISTINCT, joins and subquery scope remain barriers unless an independent
 * singleton constant table can be normalized. Projections are not deduplications.
 */
public class TransformProjectionPlacement extends TransformCopy {
    /** Create the stateless transform. */
    public TransformProjectionPlacement() {}

    @Override
    public Op transform(OpProject project, Op child) {
        return place(project.getVars(), child);
    }

    private static Op place(List<Var> vars, Op input) {
        // Keep the original projection boundary even if all columns are already live.
        if (new HashSet<>(vars).containsAll(OpVars.visibleVars(input)))
            return new OpProject(input, vars);
        if (input instanceof OpProject inner) {
            List<Var> available = vars.stream().filter(inner.getVars()::contains).toList();
            return new OpProject(place(available, inner.getSubOp()), vars);
        }
        if (input instanceof OpOrder order && TransformOrderUnion.supported(order.getConditions())) {
            List<Var> live =
                    live(vars, ExprVars.getVarsMentioned(order.getConditions()), order.getSubOp());
            return new OpProject(new OpOrder(place(live, order.getSubOp()), order.getConditions()), vars);
        }
        if (input instanceof OpFilter filter
                && filter.getExprs().getList().stream().allMatch(ExprSafety::isStableAndTransparent)) {
            List<Var> live = live(vars, ExprVars.getVarsMentioned(filter.getExprs()), filter.getSubOp());
            return new OpProject(
                    OpFilter.filterBy(filter.getExprs(), place(live, filter.getSubOp())), vars);
        }
        if (input instanceof OpUnion union) {
            return new OpProject(
                    OpUnion.create(place(vars, union.getLeft()), place(vars, union.getRight())), vars);
        }
        if (input instanceof OpDisjunction union) {
            OpDisjunction result = OpDisjunction.create();
            union.getElements().forEach(member -> result.add(place(vars, member)));
            return new OpProject(result, vars);
        }
        Op normalized = input;
        if (input instanceof OpSequence sequence && sequence.size() == 2) {
            normalized = OpJoin.create(sequence.get(0), sequence.get(1));
        }
        normalized = TransformGroupTable.normalizeSingletonJoin(normalized);
        if (normalized instanceof OpExtend extend
                && Collections.disjoint(vars, extend.getVarExprList().getVars())
                && extend.getVarExprList().getExprs().values().stream().allMatch(e -> e.isConstant())
                && Collections.disjoint(
                        extend.getVarExprList().getVars(), OpVars.mentionedVars(extend.getSubOp()))
                && ExprSafety.hasOnlyStableTransparentExpressions(extend.getSubOp())) {
            return place(vars, extend.getSubOp());
        }
        return new OpProject(input, vars);
    }

    private static List<Var> live(List<Var> projection, Set<Var> dependencies, Op input) {
        Set<Var> result = new LinkedHashSet<>(projection);
        result.addAll(dependencies);
        result.retainAll(OpVars.visibleVars(input));
        return List.copyOf(result);
    }
}
