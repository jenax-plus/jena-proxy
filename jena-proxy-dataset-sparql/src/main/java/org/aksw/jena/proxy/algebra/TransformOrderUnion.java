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
import java.util.List;
import org.apache.jena.query.SortCondition;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.TransformCopy;
import org.apache.jena.sparql.algebra.op.*;

/**
 * ORDER(UNION(...)) -> MERGE_SORTED(ORDER(...), ...), preserving bag multiplicities.
 * Install after the standard optimizer; the execution extension is deliberately opaque to
 * subsequent algebra rewrites. Volatile and opaque sort expressions retain the ordinary sort.
 */
public class TransformOrderUnion extends TransformCopy {
    /** Create the transform; it holds no state. */
    public TransformOrderUnion() {}

    static boolean supported(List<SortCondition> conditions) {
        // Expressions can depend on endpoint-local state even when repeatable (NOW, EXISTS,
        // custom functions). Start with variables/constants, evaluated identically at either end.
        return !conditions.isEmpty() && conditions.stream().allMatch(c -> c.getExpression() != null
                && (c.getExpression().isVariable() || c.getExpression().isConstant()));
    }

    @Override
    public Op transform(OpOrder order, Op subOp) {
        Op result = distribute(subOp, order.getConditions());
        return result == null ? super.transform(order, subOp) : result;
    }

    @Override
    public Op transform(OpTopN top, Op subOp) {
        Op result = distribute(subOp, top.getConditions());
        return result == null ? super.transform(top, subOp) : new OpSlice(result, 0, top.getLimit());
    }

    private static Op distribute(Op subOp, List<SortCondition> conditions) {
        if (!supported(conditions)) return null;
        if (subOp instanceof OpFilter filter && filter.getExprs().getList().stream()
                .allMatch(ExprSafety::isStableAndTransparent)) {
            Op sorted = distribute(filter.getSubOp(), conditions);
            return sorted == null ? null : OpFilter.filterBy(filter.getExprs(), sorted);
        }
        if (subOp instanceof OpProject project && project.getVars().containsAll(
                org.apache.jena.sparql.expr.ExprVars.getVarsMentioned(conditions))) {
            Op sorted = distribute(project.getSubOp(), conditions);
            return sorted == null ? null : new OpProject(sorted, project.getVars());
        }
        if (!(subOp instanceof OpUnion || subOp instanceof OpDisjunction)) return null;
        List<Op> members = new ArrayList<>();
        collect(subOp, members);
        return new OpMergeSorted(members.stream().map(op -> (Op)new OpOrder(op, conditions)).toList(),
                conditions, new OpOrder(subOp, conditions));
    }

    private static void collect(Op op, List<Op> members) {
        if (op instanceof OpUnion union) {
            collect(union.getLeft(), members);
            collect(union.getRight(), members);
        } else if (op instanceof OpDisjunction disjunction) {
            disjunction.getElements().forEach(member -> collect(member, members));
        } else {
            members.add(op);
        }
    }
}
