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

import java.util.Collections;
import org.apache.jena.sparql.algebra.*;
import org.apache.jena.sparql.algebra.op.*;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.core.VarExprList;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.expr.NodeValue;

/**
 * Normalize a singleton binding table joined directly below a group into an Extend. The table
 * variables must be fully bound and absent from the other operand, including its expressions. This
 * exposes branch constants to TransformPullExtend without changing ordinary VALUES-before-SERVICE
 * selection elsewhere in a query.
 */
public class TransformGroupTable extends TransformCopy {
    /** Create the transform; it holds no state. */
    public TransformGroupTable() {}

    @Override
    public Op transform(OpGroup group, Op subOp) {
        if (subOp instanceof OpJoin join) {
            Op normalized = normalize(join.getLeft(), join.getRight());
            if (normalized == null) normalized = normalize(join.getRight(), join.getLeft());
            if (normalized != null) {
                return new OpGroup(normalized, group.getGroupVars(), group.getAggregators());
            }
        }
        return super.transform(group, subOp);
    }

    private static Op normalize(Op candidate, Op other) {
        if (!(candidate instanceof OpTable table)
                || table.getTable().size() != 1
                || !Collections.disjoint(table.getTable().getVars(), OpVars.mentionedVars(other))) {
            return null;
        }
        Binding binding = table.getTable().rows().next();
        VarExprList assignments = new VarExprList();
        for (Var var : table.getTable().getVars()) {
            if (!binding.contains(var)) return null;
            assignments.add(var, NodeValue.makeNode(binding.get(var)));
        }
        return assignments.isEmpty() ? other : OpExtend.create(other, assignments);
    }

    /**
     * Normalize a singleton-table join, returning the same operation when ineligible.
     *
     * @param op the operation to normalize
     * @return the operation with a singleton-table join replaced by an Extend, or op
     */
    public static Op normalizeSingletonJoin(Op op) {
        if (op instanceof OpJoin join) {
            Op result = normalize(join.getLeft(), join.getRight());
            if (result == null) result = normalize(join.getRight(), join.getLeft());
            if (result != null) return result;
        }
        return op;
    }
}
