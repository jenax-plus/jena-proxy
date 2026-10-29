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

import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.OpVars;
import org.apache.jena.sparql.core.Var;

/** Query-wide allocation; includes variables outside the subtree being rewritten. */
final class RewriteVars implements Supplier<Var> {
    private final Set<Var> used;
    private int next;

    RewriteVars(Op root) {
        used = new HashSet<>(OpVars.mentionedVars(root));
    }

    @Override
    public Var get() {
        Var result;
        do {
            result = Var.alloc("__jenaProxyAgg" + next++);
        } while (!used.add(result));
        return result;
    }
}
