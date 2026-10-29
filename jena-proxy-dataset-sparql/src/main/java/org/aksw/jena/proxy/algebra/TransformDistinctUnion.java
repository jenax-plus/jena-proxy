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
import org.apache.jena.sparql.algebra.*;
import org.apache.jena.sparql.algebra.op.*;
import org.apache.jena.sparql.core.Var;

/**
 * DISTINCT(UNION(...)) -> DISTINCT(UNION(DISTINCT(...), ...)).
 * Also handles a projection between DISTINCT and UNION, using that projection in
 * every branch. Always retains final duplicate elimination across branches.
 */
public class TransformDistinctUnion extends TransformCopy {
    /** Create the transform; it holds no state. */
    public TransformDistinctUnion() {}

    @Override
    public Op transform(OpDistinct distinct, Op subOp) {
        Op input = subOp;
        List<Var> projection = null;
        if (input instanceof OpProject project) {
            projection = project.getVars();
            input = project.getSubOp();
        }
        if (!(input instanceof OpUnion || input instanceof OpDisjunction)) {
            return super.transform(distinct, subOp);
        }
        Op distributed = distribute(input, projection);
        if (distributed.equals(input)) return super.transform(distinct, subOp);
        if (projection != null) distributed = new OpProject(distributed, projection);
        return OpDistinct.create(distributed);
    }

    private static Op distribute(Op op, List<Var> projection) {
        if (op instanceof OpUnion union) {
            return OpUnion.create(distribute(union.getLeft(), projection), distribute(union.getRight(), projection));
        }
        if (op instanceof OpDisjunction disjunction) {
            OpDisjunction result = OpDisjunction.create();
            disjunction.getElements().forEach(member -> result.add(distribute(member, projection)));
            return result;
        }
        if (op instanceof OpDistinct distinct && (projection == null
                || distinct.getSubOp() instanceof OpProject project && project.getVars().equals(projection))) {
            return op;
        }
        return OpDistinct.create(projection == null ? op : new OpProject(op, projection));
    }
}
