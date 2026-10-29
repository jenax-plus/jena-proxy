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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.apache.jena.query.Query;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.OpAsQuery;
import org.apache.jena.sparql.algebra.OpVars;
import org.apache.jena.sparql.algebra.op.OpGroup;
import org.apache.jena.sparql.algebra.op.OpOrder;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.engine.Rename;
import org.apache.jena.sparql.graph.NodeTransformLib;

/** Conversion helpers for algebra fragments sent as independent queries. */
public final class AlgebraQueries {
    private AlgebraQueries() {}

    /**
     * Like OpAsQuery.asQuery, but explicitly project bare Group results (also under Order). OpAsQuery normally
     * expects the compiled Project/Extend/Group shape. For a bare Group its SELECT * omits aggregate
     * expressions from serialized SPARQL.
     */
    public static Query asQuery(Op op) {
        Query result = OpAsQuery.asQuery(op);
        Op projectionInput = op;
        while (projectionInput instanceof OpOrder order) projectionInput = order.getSubOp();
        if (projectionInput instanceof OpGroup group) {
            result.setQueryResultStar(false);
            result.getProject().clear();
            group.getGroupVars().getVars().forEach(result::addResultVar);
            group.getAggregators().forEach(agg -> result.addResultVar(agg.getVar(), agg));
        }
        return result;
    }

    /** A serializable query and its output-variable mapping back to the original algebra. */
    public record PreparedQuery(Query query, Map<Var, Var> outputMapping) {
        public PreparedQuery {
            outputMapping = Map.copyOf(outputMapping);
        }
    }

    /**
     * Restore scoped names without collapsing distinct variables, and give ARQ's allocated variables
     * explicit wire aliases. Otherwise serializers may omit their names (for example SELECT
     * (count(*)) for the internal variable ?.0). Apply outputMapping to returned bindings before
     * joining them locally.
     */
    public static PreparedQuery prepareForRemote(Op op) {
        Set<Var> mentioned = new HashSet<>(OpVars.mentionedVars(op));
        mentioned.addAll(OpVars.visibleVars(op));
        Set<Var> used = new HashSet<>(mentioned);
        Map<Var, Var> toRemote = new HashMap<>();
        int next = 0;
        for (Var original : mentioned) {
            Var remote = Var.alloc(Rename.reverseVarRename(original));
            if (Var.isAllocVar(remote) || (!remote.equals(original) && used.contains(remote))) {
                do {
                    remote = Var.alloc("__jenaProxyRemote" + next++);
                } while (used.contains(remote));
            }
            used.add(remote);
            toRemote.put(original, remote);
        }
        Op renamed =
                NodeTransformLib.transform(
                        node ->
                                node.isVariable() ? toRemote.getOrDefault(Var.alloc(node), Var.alloc(node)) : node,
                        op);
        Map<Var, Var> outputs = new HashMap<>();
        OpVars.visibleVars(op).forEach(original -> outputs.put(toRemote.get(original), original));
        return new PreparedQuery(asQuery(renamed), outputs);
    }
}
