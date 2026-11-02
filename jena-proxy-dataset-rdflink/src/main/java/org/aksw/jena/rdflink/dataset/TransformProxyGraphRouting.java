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

package org.aksw.jena.rdflink.dataset;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.jena.graph.Node;
import org.apache.jena.sparql.algebra.*;
import org.apache.jena.sparql.algebra.op.*;
import org.apache.jena.sparql.algebra.table.TableData;
import org.apache.jena.sparql.algebra.walker.Walker;
import org.apache.jena.sparql.core.BasicPattern;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.engine.binding.BindingFactory;

/** Routes composite GRAPH algebra to services without serializing the local plan as a query. */
final class TransformProxyGraphRouting extends TransformCopy {
    private final List<Node> graphs;
    private final Map<Node, Node> services;

    private TransformProxyGraphRouting(List<Node> graphs, Map<Node, Node> services) {
        this.graphs = List.copyOf(graphs);
        this.services = Map.copyOf(services);
    }

    static Op rewrite(Op root, List<Node> graphs, Map<Node, Node> services) {
        return Transformer.transformSkipService(new TransformProxyGraphRouting(graphs, services), root);
    }

    private static boolean switchesGraph(Op body) {
        AtomicBoolean result = new AtomicBoolean();
        Walker.walkSkipService(
                body,
                new OpVisitorBase() {
                    @Override
                    public void visit(OpGraph graph) {
                        result.set(true);
                    }

                    @Override
                    public void visit(OpQuad quad) {
                        result.set(true);
                    }

                    @Override
                    public void visit(OpQuadPattern quad) {
                        result.set(true);
                    }

                    @Override
                    public void visit(OpQuadBlock quad) {
                        result.set(true);
                    }

                    @Override
                    public void visit(OpDatasetNames names) {
                        result.set(true);
                    }
                },
                null,
                null,
                null);
        return result.get();
    }

    private Op route(Node name, Op originalBody, Op transformedBody) {
        Node service = services.get(name);
        if (service == null || switchesGraph(originalBody)) {
            // A nested graph switch resolves against the composite dataset, not
            // the selected remote graph. Preserve its transformed children locally.
            return new OpGraph(name, transformedBody);
        }
        return new OpService(service, originalBody, false);
    }

    @Override
    public Op transform(OpGraph graph, Op child) {
        Node name = graph.getNode();
        if (!name.isVariable()) return route(name, graph.getSubOp(), child);
        if (graphs.isEmpty()) return OpTable.empty();
        Op result = null;
        for (Node alias : graphs) {
            Var var = Var.alloc(name);
            Op value =
                    OpTable.create(new TableData(List.of(var), List.of(BindingFactory.binding(var, alias))));
            Op branch = OpJoin.create(value, route(alias, graph.getSubOp(), child));
            result = result == null ? branch : OpUnion.create(result, branch);
        }
        return result;
    }

    @Override
    public Op transform(OpQuadPattern quads) {
        if (quads.isDefaultGraph() || quads.isUnionGraph()) return quads;
        OpBGP body = new OpBGP(quads.getBasicPattern());
        return transform(new OpGraph(quads.getGraphNode(), body), body);
    }

    @Override
    public Op transform(OpQuad quad) {
        if (Quad.isDefaultGraph(quad.getQuad().getGraph())
                || Quad.isUnionGraph(quad.getQuad().getGraph())) return quad;
        BasicPattern pattern = new BasicPattern();
        pattern.add(quad.getQuad().asTriple());
        return transform(new OpQuadPattern(quad.getQuad().getGraph(), pattern));
    }

    @Override
    public Op transform(OpQuadBlock block) {
        if (block.isEmpty()) return block;
        Op result = OpTable.unit();
        for (OpQuadPattern group : block.convert()) result = OpJoin.create(result, transform(group));
        return result;
    }
}
