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

package org.aksw.jena.proxy.algebra.udg;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.sparql.algebra.*;
import org.apache.jena.sparql.algebra.op.*;
import org.apache.jena.sparql.core.BasicPattern;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.core.VarExprList;
import org.apache.jena.sparql.expr.E_Exists;
import org.apache.jena.sparql.expr.ExprTransformCopy;
import org.apache.jena.sparql.expr.ExprVar;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.graph.NodeTransformLib;
import org.apache.jena.sparql.util.Context;
import org.apache.jena.sparql.util.Symbol;

/**
 * Opt-in union-default-graph (UDG) virtualization for triple-pattern algebra. Only named graphs
 * contribute to the virtual default graph; explicit named GRAPH patterns and SERVICE bodies retain
 * their dataset semantics. Each default-graph triple is matched independently, with duplicate
 * triples removed before joins by default and without exposing the generated graph variable. Set
 * {@link #DEDUPLICATE} false to retain cross-graph multiplicities.
 *
 * <p>Use {@link #rewrite(Op)} on the complete query algebra before optimization/source selection.
 * The private worker cannot be accidentally used without its graph-scope visitors, unlike Jena's
 * public TransformUnionQuery constructor. Traversal includes EXISTS/NOT EXISTS.
 *
 * <p>Default-graph property paths, property functions, procedures and opaque algebra extensions are
 * rejected in strict mode (the default). Set {@link #STRICT} false in a caller's Context for a
 * best-effort rewrite that leaves unsupported operators in place. This is not a DatasetGraph
 * wrapper: graph access performed by custom scalar functions is not virtualized. The caller remains
 * responsible for selecting the input SPARQL dataset.
 */
public final class TransformUnionDefaultGraph {
    /**
     * False selects best-effort handling: unsupported operators keep their normal execution
     * semantics. In particular, a default-graph path then uses the physical default graph, so paths
     * crossing named graphs may be missed. Missing settings default to strict mode.
     */
    public static final Symbol STRICT = Symbol.create("https://w3id.org/aksw/jena/proxy#udgStrict");

    /**
     * False skips per-triple cross-graph DISTINCTs, selecting bag semantics. A triple found in
     * several graphs contributes once per graph, including ground triples; joins can multiply these
     * contributions. Missing settings default to true.
     */
    public static final Symbol DEDUPLICATE =
            Symbol.create("https://w3id.org/aksw/jena/proxy#udgDeduplicate");

    private TransformUnionDefaultGraph() {}

    /**
     * Rewrite a complete tree in strict mode with deduplication; does not install an engine or mutate
     * a dataset.
     *
     * @param root the complete algebra tree to rewrite
     * @return the rewritten algebra tree
     */
    public static Op rewrite(Op root) {
        return rewrite(root, null);
    }

    /**
     * Read STRICT and DEDUPLICATE from the supplied context; neither context nor dataset is modified.
     *
     * @param root the complete algebra tree to rewrite
     * @param context the context supplying STRICT and DEDUPLICATE; null uses the strict, deduplicating
     *        defaults
     * @return the rewritten algebra tree
     */
    public static Op rewrite(Op root, Context context) {
        boolean strict = context == null || !context.isFalse(STRICT);
        boolean deduplicate = context == null || !context.isFalse(DEDUPLICATE);
        Worker worker = new Worker(java.util.Objects.requireNonNull(root), strict, deduplicate);
        OpVisitor before =
                new OpVisitorBase() {
                    @Override
                    public void visit(OpGraph graph) {
                        worker.graphs.push(graph.getNode());
                    }
                };
        OpVisitor after =
                new OpVisitorBase() {
                    @Override
                    public void visit(OpGraph graph) {
                        if (!worker.graphs.pop().equals(graph.getNode())) {
                            throw new IllegalStateException("Unbalanced GRAPH scope");
                        }
                    }
                };
        return Transformer.transformSkipService(worker, new ExprTransformCopy(), root, before, after);
    }

    private static boolean virtualGraph(Node graph) {
        return Quad.isDefaultGraph(graph) || Quad.isUnionGraph(graph);
    }

    private static final class Worker extends TransformCopy {
        private final Deque<Node> graphs = new ArrayDeque<>();
        private final Set<Var> used;
        private final boolean strict;
        private final boolean deduplicate;
        private int next;

        Worker(Op root, boolean strict, boolean deduplicate) {
            this.strict = strict;
            this.deduplicate = deduplicate;
            graphs.push(Quad.defaultGraphNodeGenerated);
            used = new HashSet<>(OpVars.mentionedVars(root));
        }

        private boolean inDefaultGraph() {
            return virtualGraph(graphs.peek());
        }

        private Var freshGraph() {
            Var result;
            do {
                result = Var.alloc("__jenaProxyUdgGraph" + next++);
            } while (!used.add(result));
            return result;
        }

        private Op triple(Triple triple) {
            BasicPattern pattern = new BasicPattern();
            pattern.add(triple);
            OpBGP bgp = new OpBGP(pattern);
            Var graphVar = freshGraph();
            Op graph = new OpGraph(graphVar, bgp);
            var vars = new ArrayList<>(OpVars.visibleVars(bgp));
            if (deduplicate && vars.isEmpty()) {
                // Ground patterns are existence tests. FILTER EXISTS keeps zero columns
                // without relying on a zero-column SELECT projection (not portable SPARQL).
                // Jena 6.2's OpAsQuery cleanup can strip the required EXISTS braces
                // around a lone GRAPH. A true filter keeps a two-element group intact.
                Op existencePattern = OpFilter.filter(NodeValue.TRUE, graph);
                return OpFilter.filter(new E_Exists(existencePattern), OpTable.unit());
            }
            // The outer Project is an execution boundary: ARQ must not apply DISTINCT
            // across multiple incoming bindings when it selects a streaming join.
            Op projected = new OpProject(graph, vars);
            return new OpProject(deduplicate ? OpDistinct.create(projected) : projected, vars);
        }

        private Op pattern(BasicPattern pattern) {
            // Query blank-node variables are existential within this BGP. Once the BGP
            // is split into subqueries, give their shared join positions ordinary fresh
            // names so that ARQ/subquery serialization does not drop their identity.
            var originalVars = OpVars.visibleVars(new OpBGP(pattern));
            Map<Node, Node> anonymous = new HashMap<>();
            for (Var var : originalVars) {
                if (Var.isBlankNodeVar(var)) anonymous.put(var, freshGraph());
            }
            Op result = OpTable.unit();
            for (Triple triple : pattern) {
                Triple renamed =
                        NodeTransformLib.transform(node -> anonymous.getOrDefault(node, node), triple);
                result = OpJoin.create(result, triple(renamed));
            }
            if (anonymous.isEmpty()) return result;
            VarExprList restore = new VarExprList();
            anonymous.forEach(
                    (original, alias) -> restore.add(Var.alloc(original), new ExprVar(Var.alloc(alias))));
            return new OpProject(OpExtend.create(result, restore), new ArrayList<>(originalVars));
        }

        @Override
        public Op transform(OpBGP bgp) {
            return inDefaultGraph() && !bgp.getPattern().isEmpty() ? pattern(bgp.getPattern()) : bgp;
        }

        @Override
        public Op transform(OpTriple triple) {
            return inDefaultGraph() ? triple(triple.getTriple()) : triple;
        }

        @Override
        public Op transform(OpGraph graph, Op child) {
            return virtualGraph(graph.getNode()) ? child : super.transform(graph, child);
        }

        @Override
        public Op transform(OpQuadPattern quads) {
            return virtualGraph(quads.getGraphNode()) ? pattern(quads.getBasicPattern()) : quads;
        }

        @Override
        public Op transform(OpQuad quad) {
            return virtualGraph(quad.getQuad().getGraph()) ? triple(quad.getQuad().asTriple()) : quad;
        }

        @Override
        public Op transform(OpQuadBlock block) {
            if (block.getPattern().getList().stream().noneMatch(q -> virtualGraph(q.getGraph())))
                return block;
            Op result = OpTable.unit();
            for (OpQuadPattern group : block.convert()) {
                Op member = virtualGraph(group.getGraphNode()) ? pattern(group.getBasicPattern()) : group;
                result = OpJoin.create(result, member);
            }
            return result;
        }

        private void requireNamedGraph(Op op) {
            if (strict && inDefaultGraph())
                throw new UnsupportedOperationException(
                        "UDG rewrite does not support default-graph " + op.getName());
        }

        @Override
        public Op transform(OpPath path) {
            requireNamedGraph(path);
            return path;
        }

        @Override
        public Op transform(OpPropFunc function, Op child) {
            requireNamedGraph(function);
            return super.transform(function, child);
        }

        @Override
        public Op transform(OpProcedure procedure, Op child) {
            requireNamedGraph(procedure);
            return super.transform(procedure, child);
        }

        @Override
        public Op transform(OpExt ext) {
            if (strict)
                throw new UnsupportedOperationException(
                        "UDG rewrite cannot inspect algebra extension " + ext.getName());
            return ext;
        }
    }
}
