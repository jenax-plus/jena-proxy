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
import java.util.Optional;
import java.util.function.Supplier;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.expr.Expr;
import org.apache.jena.sparql.expr.ExprAggregator;

/** Describes partial state, merge aggregates and optional finalization for one aggregate. */
@FunctionalInterface
public interface AggregateDecomposition {
    /**
     * Return empty if this aggregate cannot be split under the caller's assumptions.
     *
     * @param aggregate the aggregate expression to decompose
     * @param freshVar supplies fresh variables reserved against the caller's algebra
     * @return a decomposition plan, or empty when no safe plan exists
     */
    Optional<Plan> decompose(ExprAggregator aggregate, Supplier<Var> freshVar);

    /**
     * A final expression, when present, is assigned to the original aggregate variable after merging.
     * Otherwise the merge aggregates must bind that variable directly. Partial state must preserve
     * errors, multiplicities and empty-input behavior.
     *
     * @param partial per-branch aggregates evaluated on each union member
     * @param merge aggregates that combine the partial results
     * @param finish expression producing the final value, or null
     */
    record Plan(List<ExprAggregator> partial, List<ExprAggregator> merge, Expr finish) {
        /** Copy both aggregate lists defensively. */
        public Plan {
            partial = List.copyOf(partial);
            merge = List.copyOf(merge);
        }
    }
}
