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
import org.apache.jena.sparql.algebra.*;
import org.apache.jena.sparql.algebra.op.*;
import org.apache.jena.sparql.core.VarExprList;
import org.apache.jena.sparql.expr.ExprAggregator;

/**
 * Split Group(Union(...)) into partial groups and a final merging group. Independent of GRAPH, quad
 * form and SERVICE. Apply once in a planning phase; this is not a fixed-point simplification rule.
 */
public class TransformGroupUnion extends TransformCopy {
    private final AggregateDecomposition decomposition;
    private final RewriteVars freshVar;

    /**
     * Reserve variables throughout the complete input algebra.
     *
     * @param root the algebra against which fresh variables are reserved
     */
    public TransformGroupUnion(Op root) {
        this(root, AggregateDecompositionRegistry.counts());
    }

    /**
     * Use a caller-configured decomposition policy.
     *
     * @param root the algebra against which fresh variables are reserved
     * @param decomposition the rules used to split aggregates
     */
    public TransformGroupUnion(Op root, AggregateDecomposition decomposition) {
        this.decomposition = decomposition;
        this.freshVar = new RewriteVars(root);
    }

    /**
     * Apply the conservative count decomposition without rewriting existing SERVICE bodies.
     *
     * @param root the algebra to rewrite
     * @return the rewritten algebra with partial and merging groups
     */
    public static Op rewrite(Op root) {
        return Transformer.transformSkipService(new TransformGroupUnion(root), root);
    }

    @Override
    public Op transform(OpGroup group, Op subOp) {
        if (!(subOp instanceof OpUnion || subOp instanceof OpDisjunction)
                || group.getAggregators().isEmpty()
                || !group.getGroupVars().getExprs().isEmpty()) {
            return super.transform(group, subOp);
        }
        List<ExprAggregator> partial = new ArrayList<>();
        List<ExprAggregator> merge = new ArrayList<>();
        VarExprList finish = new VarExprList();
        for (ExprAggregator agg : group.getAggregators()) {
            var plan = decomposition.decompose(agg, freshVar);
            if (plan.isEmpty()) {
                return super.transform(group, subOp);
            }
            partial.addAll(plan.get().partial());
            merge.addAll(plan.get().merge());
            if (plan.get().finish() != null) {
                finish.add(agg.getVar(), plan.get().finish());
            }
        }
        List<Op> members = new ArrayList<>();
        flatten(subOp, members);
        if (members.isEmpty()) {
            return super.transform(group, subOp);
        }
        Op union = null;
        for (Op member : members) {
            Op branch = new OpGroup(member, new VarExprList(group.getGroupVars()), List.copyOf(partial));
            union = union == null ? branch : OpUnion.create(union, branch);
        }
        Op result = new OpGroup(union, new VarExprList(group.getGroupVars()), merge);
        if (!finish.isEmpty()) {
            result = OpExtend.create(result, finish);
        }
        List<org.apache.jena.sparql.core.Var> outputs = new ArrayList<>(group.getGroupVars().getVars());
        group.getAggregators().forEach(a -> outputs.add(a.getVar()));
        // Do not expose merge state to SELECT *, joins or outer DISTINCT.
        return new OpProject(result, outputs);
    }

    private static void flatten(Op op, List<Op> members) {
        if (op instanceof OpUnion union) {
            flatten(union.getLeft(), members);
            flatten(union.getRight(), members);
        } else if (op instanceof OpDisjunction disjunction) {
            disjunction.getElements().forEach(member -> flatten(member, members));
        } else {
            members.add(op);
        }
    }
}
