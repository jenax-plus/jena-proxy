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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.apache.jena.graph.Node;
import org.apache.jena.sparql.algebra.*;
import org.apache.jena.sparql.algebra.op.*;
import org.apache.jena.sparql.algebra.table.TableData;
import org.apache.jena.sparql.algebra.walker.Walker;
import org.apache.jena.sparql.core.BasicPattern;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.engine.binding.BindingFactory;
import org.apache.jena.sparql.expr.E_SameTerm;
import org.apache.jena.sparql.expr.ExprTransformCopy;
import org.apache.jena.sparql.expr.ExprVar;
import org.apache.jena.sparql.expr.NodeValue;

/**
 * Routes composite GRAPH algebra to services without serializing the local plan as a query. Within
 * LATERAL right operands, graph aliases are matched by local GRAPH selectors rather than assigned
 * by VALUES: Jena's LATERAL injector overwrites conflicting table bindings, which can otherwise
 * report one service's results under another graph name. Scope tracking includes subqueries, nested
 * LATERAL and expression patterns, while existing SERVICE bodies stay opaque. Outside those scopes,
 * singleton tables retain the usual pushdown optimizations.
 */
final class TransformProxyGraphRouting extends TransformCopy {
    private final List<Node> graphs;
    private final Map<Node, Node> services;
    private final Deque<Scope> scopes = new ArrayDeque<>();

    private TransformProxyGraphRouting(List<Node> graphs, Map<Node, Node> services) {
        this.graphs = List.copyOf(graphs);
        this.services = Map.copyOf(services);
    }

    static Op rewrite(Op root, List<Node> graphs, Map<Node, Node> services) {
        TransformProxyGraphRouting worker = new TransformProxyGraphRouting(graphs, services);
        return Transformer.transformSkipService(
                worker,
                new ExprTransformCopy(),
                root,
                new VisitEach(worker::enter),
                new VisitEach(worker::leave));
    }

    private static final class Scope {
        final Op op;
        final boolean correlated;
        int children;

        Scope(Op op, boolean correlated) {
            this.op = op;
            this.correlated = correlated;
        }
    }

    private void enter(Op op) {
        Scope parent = scopes.peek();
        // Count visits rather than compare child identities: both operands can
        // share the same Op object. A LATERAL has no expression children.
        boolean correlated =
                parent != null
                        && (parent.correlated || parent.op instanceof OpLateral && parent.children == 1);
        if (parent != null) ++parent.children;
        scopes.push(new Scope(op, correlated));
    }

    private void leave(Op op) {
        if (scopes.pop().op != op) throw new IllegalStateException("Unbalanced routing scope");
    }

    /** Adapt traversal hooks without walking children a second time, including opaque extensions. */
    private static final class VisitEach extends OpVisitorByTypeBase {
        private final Consumer<Op> action;

        VisitEach(Consumer<Op> action) {
            this.action = action;
        }

        @Override
        protected void visit0(Op0 op) {
            action.accept(op);
        }

        @Override
        protected void visit1(Op1 op) {
            action.accept(op);
        }

        @Override
        protected void visit2(Op2 op) {
            action.accept(op);
        }

        @Override
        protected void visitN(OpN op) {
            action.accept(op);
        }

        @Override
        protected void visitFilter(OpFilter op) {
            action.accept(op);
        }

        @Override
        protected void visitLeftJoin(OpLeftJoin op) {
            action.accept(op);
        }

        @Override
        protected void visitExt(OpExt op) {
            action.accept(op);
        }
    }

    /**
     * Match an alias without rebinding a LATERAL input variable. GRAPH enumeration stays local; the
     * alias filter rejects other branches before their service is evaluated. Unlike a filter on
     * VALUES, it does not depend on retaining a tautology over a preassigned table value. These
     * generated selectors must not undergo graph routing again.
     */
    private Op selector(Var var, Node alias) {
        if (!scopes.isEmpty() && scopes.peek().correlated) {
            return OpFilter.filter(
                    new E_SameTerm(new ExprVar(var), NodeValue.makeNode(alias)),
                    new OpGraph(var, OpTable.unit()));
        }
        return OpTable.create(new TableData(List.of(var), List.of(BindingFactory.binding(var, alias))));
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
            Op value = selector(var, alias);
            Op branch = OpJoin.create(value, route(alias, graph.getSubOp(), child));
            result = result == null ? branch : OpUnion.create(result, branch);
        }
        return result;
    }

    @Override
    public Op transform(OpDatasetNames names) {
        // Quad-form GRAPH ?g {} becomes OpDatasetNames, which the generic
        // in-memory executor cannot run. Enumeration needs only local metadata.
        return new OpGraph(names.getGraphNode(), OpTable.unit());
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
