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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.aksw.jena.proxy.algebra.AlgebraQueries;
import org.aksw.jena.proxy.algebra.TransformDistinctPlacement;
import org.aksw.jena.proxy.algebra.TransformDistinctService;
import org.aksw.jena.proxy.algebra.TransformGroupService;
import org.aksw.jena.proxy.algebra.TransformGroupTable;
import org.aksw.jena.proxy.algebra.TransformGroupUnion;
import org.aksw.jena.proxy.algebra.TransformOrderService;
import org.aksw.jena.proxy.algebra.TransformOrderUnion;
import org.aksw.jena.proxy.algebra.TransformPullExtend;
import org.aksw.jena.proxy.algebra.udg.TransformUnionDefaultGraph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.Query;
import org.apache.jena.sparql.ARQConstants;
import org.apache.jena.sparql.algebra.Algebra;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.Transformer;
import org.apache.jena.sparql.algebra.op.OpGroup;
import org.apache.jena.sparql.algebra.optimize.Optimize;
import org.apache.jena.sparql.algebra.optimize.RewriteFactory;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.DatasetGraphMapLink;
import org.apache.jena.sparql.core.GraphView;
import org.apache.jena.sparql.engine.Plan;
import org.apache.jena.sparql.engine.QueryEngineFactory;
import org.apache.jena.sparql.engine.QueryIterator;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.engine.binding.BindingFactory;
import org.apache.jena.sparql.engine.iterator.QueryIter;
import org.apache.jena.sparql.engine.iterator.QueryIterCommonParent;
import org.apache.jena.sparql.engine.main.QueryEngineMain;
import org.apache.jena.sparql.expr.aggregate.*;
import org.apache.jena.sparql.service.ServiceExecutorRegistry;
import org.apache.jena.sparql.util.Context;
import org.apache.jena.sparql.util.Symbol;

/** Delegates named proxy graph patterns in a link-based composite dataset. */
public class QueryEngineFactoryProxyGraphs implements QueryEngineFactory {
    /** Create the factory; it holds no state. */
    public QueryEngineFactoryProxyGraphs() {}

    /** Set false on the dataset or execution request to opt out of composite proxy dispatch. */
    public static final Symbol ENABLED =
            Symbol.create("https://w3id.org/aksw/jena/proxy#proxyGraphsEnabled");

    private static boolean enabled(DatasetGraph dataset, Context requestContext) {
        Context settings =
                requestContext != null && requestContext.isDefined(ENABLED)
                        ? requestContext
                        : dataset.getContext();
        return !settings.isFalse(ENABLED);
    }

    /** Standard aggregates over variables/constants have no query-local expression state. */
    private static boolean canDelegate(OpGroup group) {
        if (!group.getGroupVars().getExprs().isEmpty()) return false;
        Set<Class<? extends Aggregator>> supported =
                Set.of(
                        AggCount.class,
                        AggCountVar.class,
                        AggCountDistinct.class,
                        AggCountVarDistinct.class,
                        AggSum.class,
                        AggSumDistinct.class,
                        AggAvg.class,
                        AggAvgDistinct.class,
                        AggMin.class,
                        AggMinDistinct.class,
                        AggMax.class,
                        AggMaxDistinct.class,
                        AggSample.class,
                        AggSampleDistinct.class,
                        AggGroupConcat.class,
                        AggGroupConcatDistinct.class);
        return group.getAggregators().stream()
                .allMatch(
                        expr -> {
                            Aggregator agg = expr.getAggregator();
                            return supported.contains(agg.getClass())
                                    && (agg.getExprList() == null
                                            || agg.getExprList().getList().stream()
                                                    .allMatch(e -> e.isVariable() || e.isConstant()));
                        });
    }

    /** Install only in this request, preserving the dataset's configured optimizer. */
    private static void configureOptimizer(Context requestContext, Set<Node> services) {
        RewriteFactory configured = requestContext.get(ARQConstants.sysOptimizerFactory);
        RewriteFactory delegate = configured != null ? configured : Optimize.getFactory();
        requestContext.set(
                ARQConstants.sysOptimizerFactory,
                (RewriteFactory)
                        context -> {
                            var downstream = delegate.create(context);
                            return op -> {
                                Op planned = TransformGroupUnion.rewrite(op);
                                planned = Transformer.transformSkipService(new TransformGroupTable(), planned);
                                planned = TransformPullExtend.transform(planned);
                                planned =
                                        Transformer.transformSkipService(
                                                new TransformGroupService(
                                                        service -> services.contains(service.getService()),
                                                        QueryEngineFactoryProxyGraphs::canDelegate),
                                                planned);
                                planned = TransformDistinctPlacement.rewrite(planned, true);
                                planned =
                                        Transformer.transformSkipService(
                                                new TransformDistinctService(
                                                        service -> services.contains(service.getService())),
                                                planned);
                                // Add execution extensions last, after standard scoping and TopN placement.
                                planned = downstream.rewrite(planned);
                                planned = Transformer.transformSkipService(new TransformOrderUnion(), planned);
                                return Transformer.transformSkipService(
                                        new TransformOrderService(service -> services.contains(service.getService())),
                                        planned);
                            };
                        });
    }

    private static Map<Node, GraphView> proxyGraphs(DatasetGraph dataset) {
        Map<Node, GraphView> result = new LinkedHashMap<>();
        // Only inspect a local graph collection: discovery on a remote dataset
        // could itself perform a query, and wrappers may change visible data.
        if (dataset instanceof DatasetGraphMapLink) {
            dataset
                    .listGraphNodes()
                    .forEachRemaining(
                            name -> {
                                if (dataset.getGraph(name) instanceof GraphView view
                                        && view.getDataset() instanceof DatasetGraphOverRDFLink) {
                                    result.put(name, view);
                                }
                            });
        }
        return result;
    }

    @Override
    public boolean accept(Query query, DatasetGraph dataset, Context context) {
        return dataset instanceof DatasetGraphMapLink
                && enabled(dataset, context)
                && (ProxyDatasetSettings.unionDefaultGraph(dataset, context)
                        || !proxyGraphs(dataset).isEmpty());
    }

    @Override
    public boolean accept(Op op, DatasetGraph dataset, Context context) {
        return dataset instanceof DatasetGraphMapLink
                && enabled(dataset, context)
                && (ProxyDatasetSettings.unionDefaultGraph(dataset, context)
                        || !proxyGraphs(dataset).isEmpty());
    }

    @Override
    public Plan create(Op op, DatasetGraph dataset, Binding binding, Context context) {
        return createPlan(op, null, dataset, binding, context);
    }

    @Override
    public Plan create(Query query, DatasetGraph dataset, Binding binding, Context context) {
        if (query.hasDatasetDescription() || query.isDescribeType()) {
            throw new UnsupportedOperationException(
                    "Proxy graph federation does not support dataset clauses or DESCRIBE");
        }
        Query input = query.cloneQuery();
        if (input.isSelectType() && input.isQueryResultStar()) {
            input.getResultVars();
            input.setQueryResultStar(false);
        }
        return createPlan(Algebra.compile(input), input, dataset, binding, context);
    }

    private Plan createPlan(
            Op op, Query query, DatasetGraph dataset, Binding binding, Context context) {
        if (dataset.getDefaultGraph() instanceof GraphView view
                && view.getDataset() instanceof DatasetGraphOverRDFLink) {
            throw new UnsupportedOperationException(
                    "Use proxy named graphs with a local default graph in a composite dataset");
        }
        Map<Node, GraphView> views = proxyGraphs(dataset);
        Map<Node, GraphView> routes = new LinkedHashMap<>();
        Map<Node, Node> services = new LinkedHashMap<>();
        // The UUID avoids clashes with user-written SERVICE IRIs that reuse a graph name; the prefix
        // aids debugging.
        views.forEach(
                (name, view) -> {
                    String graphName = name.isURI() ? name.getURI() : name.toString();
                    String prefix = URLEncoder.encode(graphName, StandardCharsets.UTF_8);
                    Node service =
                            NodeFactory.createURI("urn:jena-proxy:graph:" + prefix + ":" + UUID.randomUUID());
                    services.put(name, service);
                    routes.put(service, view);
                });
        Context requestContext = ProxyDatasetSettings.requestContext(dataset, context);
        ServiceExecutorRegistry registry =
                ServiceExecutorRegistry.chooseRegistry(requestContext).copy();
        registry.add(
                (opExecute, opOriginal, parent, execCxt) -> {
                    Node serviceNode = opExecute.getService();
                    GraphView view = routes.get(serviceNode);
                    if (view == null) {
                        return null;
                    }

                    // Explicit aliases are required for allocated aggregate outputs when
                    // the query is serialized by RDFLinkHTTP. Preserve original scoped
                    // identities when restoring the result bindings.
                    var prepared = AlgebraQueries.prepareForRemote(opExecute.getSubOp());
                    Query remoteQuery = prepared.query();

                    Query remote =
                            QueryGraphScope.rewrite(remoteQuery, view.getGraphName(), BindingFactory.empty());
                    Plan plan =
                            new PlanOverRDFLink(
                                    (DatasetGraphOverRDFLink) view.getDataset(),
                                    remote,
                                    opExecute.getSubOp(),
                                    execCxt.getContext());

                    QueryIterator qIter = plan.iterator();
                    qIter = QueryIter.map(qIter, prepared.outputMapping());

                    return new QueryIterCommonParent(qIter, parent, execCxt);
                });
        ServiceExecutorRegistry.set(requestContext, registry);
        // A non-empty initial binding may correlate service evaluation with the
        // aggregate's input. Preserve that execution path until it has a proven rewrite.
        if (binding.isEmpty()) {
            configureOptimizer(requestContext, Set.copyOf(routes.keySet()));
        }
        // UDG changes query semantics and must run before source selection, even
        // when ARQ optimization is disabled. Keep the UDG and routing rules separate.
        Op virtual =
                ProxyDatasetSettings.unionDefaultGraph(dataset, requestContext)
                        ? TransformUnionDefaultGraph.rewrite(op, requestContext)
                        : op;
        Op routed =
                TransformProxyGraphRouting.rewrite(
                        virtual,
                        org.apache.jena.atlas.iterator.Iter.toList(dataset.listGraphNodes()),
                        services);
        if (query == null)
            return new QueryEngineMain(routed, dataset, binding, requestContext).getPlan();
        return new QueryEngineMain(query, dataset, binding, requestContext) {
            @Override
            protected Op createOp(Query ignored) {
                return routed;
            }
        }.getPlan();
    }
}
