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
import java.util.function.Predicate;
import org.apache.jena.sparql.algebra.*;
import org.apache.jena.sparql.algebra.op.*;

/** Composition of independent order-placement, union-distribution and service-delegation rules. */
public final class OrderPlanning {
    private OrderPlanning() {}

    /**
     * Plan sorted branches, including nested unions exposed behind branch projections/DISTINCTs.
     * Invoke after ordinary optimization; callers must supply an order-preserving DISTINCT contract.
     * No UDG or source metadata is required. Existing service bodies remain opaque.
     *
     * @param root algebra to plan
     * @param allowed services eligible for ordering delegation
     * @return planned algebra, retaining ordinary Jena DISTINCT operators
     */
    public static Op rewrite(Op root, Predicate<OpService> allowed) {
        return Transformer.transformSkipService(new TransformOrderService(allowed), distribute(root));
    }

    private static Op distribute(Op root) {
        Op placed = Transformer.transformSkipService(new TransformOrderPlacement(), root);
        Op distributed = Transformer.transformSkipService(new TransformOrderUnion(), placed);
        // A union member may itself hide another union behind DISTINCT/project. Its
        // newly introduced Order was not in the original tree walked by the first pass.
        // Recurse into those branch plans explicitly; do not expose placeholders or
        // rewrite effectiveOp fallbacks, and do not descend into remote service bodies.
        return Transformer.transformSkipService(
                new TransformCopy() {
                    @Override
                    public Op transform(OpExt ext) {
                        if (!(ext instanceof OpMergeSorted merge)) return ext;
                        List<Op> branches =
                                merge.getBranches().stream().map(OrderPlanning::distribute).toList();
                        return branches.equals(merge.getBranches())
                                ? merge
                                : new OpMergeSorted(branches, merge.getConditions(), merge.effectiveOp());
                    }
                },
                distributed);
    }
}
