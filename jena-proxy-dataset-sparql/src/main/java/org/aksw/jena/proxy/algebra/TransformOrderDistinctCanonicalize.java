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

import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.TransformCopy;
import org.apache.jena.sparql.algebra.op.*;
import org.apache.jena.sparql.expr.ExprVars;

/**
 * Normalize ORDER(DISTINCT(...)) to the DISTINCT/PROJECT/ORDER modifier order recognized by
 * OpAsQuery, avoiding an unnecessary remote subquery. This is a wire-query normalization,
 * deliberately separate from local order placement. Assumes order-preserving DISTINCT execution, as
 * ordinary SPARQL requires. Only variable/constant sort keys are eligible; slices and projection
 * scopes are retained. Callers should use Transformer.transformSkipService so existing nested
 * SERVICE bodies remain opaque.
 */
public class TransformOrderDistinctCanonicalize extends TransformCopy {
    /** Create the stateless normalization transform. */
    public TransformOrderDistinctCanonicalize() {}

    @Override
    public Op transform(OpOrder order, Op child) {
        if (!TransformOrderUnion.supported(order.getConditions())
                || !(child instanceof OpDistinct distinct)) {
            return super.transform(order, child);
        }
        Op input = withoutDistinct(distinct.getSubOp());
        if (input instanceof OpProject project) {
            // Sorting outside an original narrow projection must not resurrect
            // variables that are unbound at that scope.
            if (!project.getVars().containsAll(ExprVars.getVarsMentioned(order.getConditions()))) {
                return super.transform(order, child);
            }
            input =
                    new OpProject(
                            new OpOrder(withoutDistinct(project.getSubOp()), order.getConditions()),
                            project.getVars());
        } else {
            input = new OpOrder(input, order.getConditions());
        }
        return OpDistinct.create(input);
    }

    // An outer DISTINCT dominates adjacent DISTINCTs, also across a plain
    // projection. Do not look through slices, expressions or other operators.
    private static Op withoutDistinct(Op input) {
        while (input instanceof OpDistinct distinct) input = distinct.getSubOp();
        return input;
    }
}
