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

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.Query;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.OpAsQuery;
import org.apache.jena.sparql.algebra.OpVars;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.DatasetGraphMapLink;
import org.apache.jena.sparql.core.GraphView;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.engine.Plan;
import org.apache.jena.sparql.engine.QueryEngineFactory;
import org.apache.jena.sparql.engine.QueryIterator;
import org.apache.jena.sparql.engine.Rename;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.engine.binding.BindingFactory;
import org.apache.jena.sparql.engine.iterator.QueryIter;
import org.apache.jena.sparql.engine.iterator.QueryIterCommonParent;
import org.apache.jena.sparql.engine.main.QueryEngineMain;
import org.apache.jena.sparql.service.ServiceExecutorRegistry;
import org.apache.jena.sparql.syntax.Element;
import org.apache.jena.sparql.syntax.ElementData;
import org.apache.jena.sparql.syntax.ElementGroup;
import org.apache.jena.sparql.syntax.ElementNamedGraph;
import org.apache.jena.sparql.syntax.ElementService;
import org.apache.jena.sparql.syntax.ElementUnion;
import org.apache.jena.sparql.syntax.syntaxtransform.ElementTransformCopyBase;
import org.apache.jena.sparql.syntax.syntaxtransform.QueryTransformOps;
import org.apache.jena.sparql.util.Context;

/** Delegates named proxy graph patterns in a link-based composite dataset. */
public class QueryEngineFactoryProxyGraphs implements QueryEngineFactory {
    private static Map<Node, GraphView> proxyGraphs(DatasetGraph dataset) {
        Map<Node, GraphView> result = new LinkedHashMap<>();
        // Only inspect a local graph collection: discovery on a remote dataset
        // could itself perform a query, and wrappers may change visible data.
        if (dataset instanceof DatasetGraphMapLink) {
            dataset.listGraphNodes().forEachRemaining(name -> {
                if (dataset.getGraph(name) instanceof GraphView view
                        && view.getDataset() instanceof DatasetGraphOverRDFLink) {
                    result.put(name, view);
                }
            });
        }
        return result;
    }

    /** Whether a pattern switches graphs in the current dataset (outside SERVICE). */
    private static boolean hasLocalGraph(Element pattern) {
        Query query = new Query();
        query.setQuerySelectType();
        query.setQueryResultStar(true);
        query.setQueryPattern(pattern);
        Query withoutGraphs = QueryTransformOps.transform(query, new ElementTransformCopyBase() {
            @Override
            public Element transform(ElementNamedGraph element, Node name, Element child) {
                return new ElementData();
            }

            @Override
            public Element transform(ElementService element, Node service, Element child) {
                return element;
            }
        });
        // QueryTransformOps also visits subqueries and EXISTS/NOT EXISTS expressions.
        return !query.equals(withoutGraphs);
    }

    @Override
    public boolean accept(Query query, DatasetGraph dataset, Context context) {
        return !proxyGraphs(dataset).isEmpty();
    }

    @Override
    public boolean accept(Op op, DatasetGraph dataset, Context context) {
        return !proxyGraphs(dataset).isEmpty();
    }

    @Override
    public Plan create(Op op, DatasetGraph dataset, Binding binding, Context context) {
        return create(OpAsQuery.asQuery(op), dataset, binding, context);
    }

    @Override
    public Plan create(Query query, DatasetGraph dataset, Binding binding, Context context) {
        if (query.hasDatasetDescription() || query.isDescribeType()) {
            throw new UnsupportedOperationException("Proxy graph federation does not support dataset clauses or DESCRIBE");
        }
        if (dataset.getDefaultGraph() instanceof GraphView view
                && view.getDataset() instanceof DatasetGraphOverRDFLink) {
            throw new UnsupportedOperationException("Use proxy named graphs with a local default graph in a composite dataset");
        }
        Map<Node, GraphView> views = proxyGraphs(dataset);
        Map<Node, GraphView> routes = new LinkedHashMap<>();
        Map<Node, Node> services = new LinkedHashMap<>();
        // XXX Why this indirection? We could just reuse the graph names?
        views.forEach((name, view) -> {
            Node service = NodeFactory.createURI("urn:jena-proxy:graph:" + UUID.randomUUID());
            services.put(name, service);
            routes.put(service, view);
        });
        Context scopedContext = context.copy();
        ServiceExecutorRegistry registry = ServiceExecutorRegistry.chooseRegistry(context).copy();
        registry.add((opExecute, opOriginal, parent, execCxt) -> {
            Node serviceNode = opExecute.getService();
            GraphView view = routes.get(serviceNode);
            if (view == null) {
                return null;
            }

            // Remap scoped variables - this is the same logic as in Service.exec()
            Op opRemote = opExecute.getSubOp();
            // Query remotePattern = OpAsQuery.asQuery(opExecute.getSubOp());

            Op opRestored = Rename.reverseVarRename(opRemote, true);
            Query remoteQuery = OpAsQuery.asQuery(opRestored);
            // Transforming: Same object means "no change"
            boolean requiresRemapping = false;
            Map<Var, Var> varMapping = null;
            if ( ! opRestored.equals(opRemote) ) {
                varMapping = new HashMap<>();
                Set<Var> originalVars = OpVars.visibleVars(opExecute);
                Set<Var> remoteVars = OpVars.visibleVars(opRestored);

                for (Var v : originalVars) {
                    if (v.getName().contains("/")) {
                        // A variable which was scope renamed so has a different name
                        String origName = v.getName().substring(v.getName().lastIndexOf('/') + 1);
                        Var remoteVar = Var.alloc(origName);
                        if (remoteVars.contains(remoteVar)) {
                            varMapping.put(remoteVar, v);
                            requiresRemapping = true;
                        }
                    } else {
                        // A variable which does not have a different name
                        if (remoteVars.contains(v))
                            varMapping.put(v, v);
                    }
                }
            }

            Query remote = QueryGraphScope.rewrite(remoteQuery,
                view.getGraphName(), BindingFactory.empty());
            Plan plan = new PlanOverRDFLink((DatasetGraphOverRDFLink)view.getDataset(), remote,
                opExecute.getSubOp(), execCxt.getContext());

            QueryIterator qIter = plan.iterator();
            if (requiresRemapping)
                qIter = QueryIter.map(qIter, varMapping);

            return new QueryIterCommonParent(qIter, parent, execCxt);
        });
        ServiceExecutorRegistry.set(scopedContext, registry);
        Query input = query.cloneQuery();
        if (input.isSelectType() && input.isQueryResultStar()) {
            input.getResultVars();
            input.setQueryResultStar(false);
        }
        Query rewritten = QueryTransformOps.transform(input, new ElementTransformCopyBase() {
            private Element proxyPattern(Node name, Element pattern, Element transformedPattern) {
                if (!services.containsKey(name) || hasLocalGraph(pattern)) {
                    // A nested GRAPH resolves against the composite dataset, not
                    // the remote dataset. Keep this scope local and retain the
                    // recursively rewritten children, which may still delegate.
                    return new ElementNamedGraph(name, transformedPattern);
                }
                return new ElementService(services.get(name), pattern, false);
            }

            @Override
            public Element transform(ElementNamedGraph element, Node name, Element child) {
                if (!name.isVariable()) {
                    return proxyPattern(name, element.getElement(), child);
                }
                ElementUnion union = new ElementUnion();
                dataset.listGraphNodes().forEachRemaining(alias -> {
                    ElementGroup branch = new ElementGroup();
                    ElementData value = new ElementData();
                    value.add(Var.alloc(name));
                    value.add(BindingFactory.binding(Var.alloc(name), alias));
                    branch.addElement(value);
                    branch.addElement(proxyPattern(alias, element.getElement(), child));
                    union.addElement(branch);
                });
                return union.getElements().isEmpty() ? new ElementData() : union;
            }

            @Override
            public Element transform(ElementService element, Node service, Element child) {
                return element;
            }
        });
        return new QueryEngineMain(rewritten, dataset, binding, scopedContext).getPlan();
    }
}
