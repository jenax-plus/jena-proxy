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

import java.util.Objects;

import org.apache.jena.query.Query;
import org.apache.jena.rdflink.RDFLink;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.engine.ExecutionContext;
import org.apache.jena.sparql.engine.PlanBase;
import org.apache.jena.sparql.engine.QueryIterator;
import org.apache.jena.sparql.exec.QueryExec;
import org.apache.jena.sparql.util.Context;

/**
 * SPARQL query execution plan over RDFLink.
 */
public class PlanOverRDFLink
    extends PlanBase
{
    /** The dataset graph. */
    protected final DatasetGraphOverRDFLink datasetGraph;

    /** The query to execute. */
    protected final Query query;

    /** The execution context. */
    protected final Context context;

    /**
     * Create a new execution plan.
     *
     * @param datasetGraph the dataset graph
     * @param query the query to execute
     * @param op the SPARQL algebra op
     * @param context the execution context
     */
    public PlanOverRDFLink(DatasetGraphOverRDFLink datasetGraph, Query query, Op op, Context context) {
        super(op, null);
        this.datasetGraph = Objects.requireNonNull(datasetGraph);
        this.query = Objects.requireNonNull(query);
        this.context = Objects.requireNonNull(context);
    }

    /**
     * Get the dataset graph.
     *
     * @return the dataset graph
     */
    public DatasetGraphOverRDFLink getDatasetGraph() {
        return datasetGraph;
    }

    /**
     * Get the query.
     *
     * @return the query
     */
    public Query getQuery() {
        return query;
    }

    /**
     * Get the execution context.
     *
     * @return the context
     */
    public Context getContext() {
        return context;
    }

    @Override
    public QueryIterator iteratorOnce() {
        ExecutionContext execCxt = ExecutionContext.create(datasetGraph, context);
        RDFLink link = datasetGraph.newLink();
        QueryExec qExecRaw;
        try {
            qExecRaw = link.query(query);
        } catch (Exception e) {
            link.close();
            throw new RuntimeException(e);
        }
        QueryExec qExec = new QueryExecWrapperCloseRDFLink(qExecRaw, link);
        QueryIterator result = new QueryIterOverQueryExec(execCxt, qExec);
        return result;
    }
}
