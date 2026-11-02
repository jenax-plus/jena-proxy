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
 * For DISTINCT(Project(R, Order(S, X))), expose an inner DISTINCT on R plus the live sort columns.
 * This permits a wider remote projection while retaining the narrow client projection and final
 * DISTINCT. Sort columns hidden by an original scope are never resurrected.
 */
public class TransformDistinctOrderProjection extends TransformCopy {
    /** Create the stateless transform. */
    public TransformDistinctOrderProjection() {}

    @Override
    public Op transform(OpDistinct distinct, Op child) {
        if (child instanceof OpProject project
                && project.getSubOp() instanceof OpOrder order
                && TransformOrderUnion.supported(order.getConditions())) {
            Set<Var> sort = ExprVars.getVarsMentioned(order.getConditions());
            if (!project.getVars().containsAll(sort) && sort.stream().allMatch(v -> v.isNamedVar())) {
                Set<Var> live = new LinkedHashSet<>(project.getVars());
                live.addAll(sort);
                live.retainAll(OpVars.visibleVars(order.getSubOp()));
                if (!live.isEmpty()) {
                    Op partial = OpDistinct.create(new OpProject(order.getSubOp(), List.copyOf(live)));
                    return OpDistinct.create(
                            new OpProject(new OpOrder(partial, order.getConditions()), project.getVars()));
                }
            }
        }
        return super.transform(distinct, child);
    }
}
