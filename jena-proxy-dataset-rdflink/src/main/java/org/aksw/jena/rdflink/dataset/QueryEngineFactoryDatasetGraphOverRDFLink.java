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

import org.apache.jena.query.Query;
import org.apache.jena.sparql.algebra.Algebra;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.OpAsQuery;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.DatasetGraphOne;
import org.apache.jena.sparql.core.GraphView;
import org.apache.jena.sparql.engine.Plan;
import org.apache.jena.sparql.engine.QueryEngineFactory;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.util.Context;

/**
 * Query engine factory for {@link org.aksw.jena.rdflink.dataset.DatasetGraphOverRDFLink}.
 *
 * @since 0.7.0
 */
public class QueryEngineFactoryDatasetGraphOverRDFLink
    implements QueryEngineFactory
{
    /**
     * Utility classes should not be instantiated.
     */
    public QueryEngineFactoryDatasetGraphOverRDFLink() {
    }

    @Override
    public boolean accept(Query query, DatasetGraph dataset, Context context) {
        boolean result = dataset instanceof DatasetGraphOverRDFLink || getProxyView(dataset) != null;
        return result;
    }

    @Override
    public Plan create(Query query, DatasetGraph dataset, Binding inputBinding, Context context) {
        GraphView view = getProxyView(dataset);
        if (view != null) {
            Query scoped = QueryGraphScope.rewrite(query, view.getGraphName(), inputBinding);
            Query select = scoped;
            if (!scoped.isSelectType()) {
                select = OpAsQuery.asQuery(Algebra.compile(scoped));
                scoped.getGraphURIs().forEach(select::addGraphURI);
            }
            return new PlanOverRDFLink((DatasetGraphOverRDFLink)view.getDataset(), select,
                Algebra.compile(query), context);
        }
        DatasetGraphOverRDFLink engineDsg = (DatasetGraphOverRDFLink)dataset;
        Op op = Algebra.compile(query);

        Query finalQuery = query.isSelectType()
            ? query
            : OpAsQuery.asQuery(op);

        Plan result = new PlanOverRDFLink(engineDsg, finalQuery, op, context);
        return result;
    }

    @Override
    public boolean accept(Op op, DatasetGraph dataset, Context context) {
        boolean result = dataset instanceof DatasetGraphOverRDFLink || getProxyView(dataset) != null;
        return result;
    }

    @Override
    public Plan create(Op op, DatasetGraph dataset, Binding inputBinding, Context context) {
        if (getProxyView(dataset) != null) {
            return create(OpAsQuery.asQuery(op), dataset, inputBinding, context);
        }
        DatasetGraphOverRDFLink engineDsg = (DatasetGraphOverRDFLink)dataset;
        Query query = OpAsQuery.asQuery(op);
        Plan result = new PlanOverRDFLink(engineDsg, query, op, context);
        return result;
    }

    private static GraphView getProxyView(DatasetGraph dataset) {
        // Do not unwrap arbitrary graph wrappers: they may change the visible data.
        return dataset instanceof DatasetGraphOne
                && dataset.getDefaultGraph() instanceof GraphView view
                && view.getDataset() instanceof DatasetGraphOverRDFLink
            ? view : null;
    }
}
