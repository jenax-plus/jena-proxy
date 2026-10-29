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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.expr.*;
import org.apache.jena.sparql.expr.aggregate.*;

/**
 * Exact-class registry: DISTINCT and custom aggregates never inherit a decomposition accidentally.
 */
public class AggregateDecompositionRegistry implements AggregateDecomposition {
    private final Map<Class<? extends Aggregator>, AggregateDecomposition> entries = new HashMap<>();

    /** Create an empty registry; register rules before decomposing. */
    public AggregateDecompositionRegistry() {}

    /**
     * Register or replace a decomposition. Configure the registry before using a transform.
     *
     * @param type the exact aggregator class to match
     * @param rule the decomposition used for that class
     * @return this registry, for chaining
     */
    public AggregateDecompositionRegistry register(
            Class<? extends Aggregator> type, AggregateDecomposition rule) {
        entries.put(type, rule);
        return this;
    }

    /**
     * Conservative defaults: COUNT(*) and COUNT of a variable or constant.
     *
     * @return a registry that decomposes only count aggregates
     */
    public static AggregateDecompositionRegistry counts() {
        AggregateDecompositionRegistry result = new AggregateDecompositionRegistry();
        AggregateDecomposition count =
                (target, vars) -> {
                    Var partial = vars.get();
                    return Optional.of(
                            new Plan(
                                    List.of(new ExprAggregator(partial, target.getAggregator())),
                                    List.of(new ExprAggregator(target.getVar(), new AggSum(new ExprVar(partial)))),
                                    null));
                };
        return result.register(AggCount.class, count).register(AggCountVar.class, count);
    }

    /**
     * Also split SUM and AVG. Opt in only when numeric regrouping is acceptable: floating-point
     * rounding, overflow and type promotion can change results. ARQ propagates an unbound partial SUM
     * through the merging SUM, preserving expression/type errors instead of silently dropping the
     * failed partition.
     *
     * @return a count registry extended with SUM and AVG rules
     */
    public static AggregateDecompositionRegistry withNumericReassociation() {
        AggregateDecompositionRegistry result = counts();
        result.register(
                AggSum.class,
                (target, vars) -> {
                    Var partial = vars.get();
                    return Optional.of(
                            new Plan(
                                    List.of(new ExprAggregator(partial, target.getAggregator())),
                                    List.of(new ExprAggregator(target.getVar(), new AggSum(new ExprVar(partial)))),
                                    null));
                });
        result.register(
                AggAvg.class,
                (target, vars) -> {
                    Expr expr = target.getAggregator().getExprList().get(0);
                    Var partialSum = vars.get();
                    Var partialCount = vars.get();
                    Var totalSum = vars.get();
                    Var totalCount = vars.get();
                    Expr count = new ExprVar(totalCount);
                    Expr finish =
                            new E_If(
                                    new E_Equals(count, NodeValue.nvZERO),
                                    new ExprVar(totalSum),
                                    new E_Divide(new ExprVar(totalSum), count));
                    return Optional.of(
                            new Plan(
                                    List.of(
                                            new ExprAggregator(partialSum, new AggSum(expr)),
                                            new ExprAggregator(partialCount, new AggCountVar(expr))),
                                    List.of(
                                            new ExprAggregator(totalSum, new AggSum(new ExprVar(partialSum))),
                                            new ExprAggregator(totalCount, new AggSum(new ExprVar(partialCount)))),
                                    finish));
                });
        return result;
    }

    @Override
    public Optional<Plan> decompose(ExprAggregator aggregate, Supplier<Var> freshVar) {
        Aggregator agg = aggregate.getAggregator();
        // Moving evaluation of NOW/RAND/BNODE/EXISTS or custom functions requires
        // a separate proof. This draft accepts only variables and RDF constants.
        if (agg.getExprList() != null
                && agg.getExprList().getList().stream().anyMatch(e -> !e.isVariable() && !e.isConstant())) {
            return Optional.empty();
        }
        AggregateDecomposition rule = entries.get(agg.getClass());
        return rule == null ? Optional.empty() : rule.decompose(aggregate, freshVar);
    }
}
