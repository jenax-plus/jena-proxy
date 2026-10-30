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
import java.util.List;
import java.util.Map;

import org.apache.jena.graph.Node;
import org.apache.jena.query.Query;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.syntax.Element;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.syntax.ElementFilter;
import org.apache.jena.sparql.syntax.ElementGroup;
import org.apache.jena.sparql.syntax.ElementNamedGraph;
import org.apache.jena.sparql.syntax.ElementService;
import org.apache.jena.sparql.syntax.syntaxtransform.ElementTransformCopyBase;
import org.apache.jena.sparql.syntax.syntaxtransform.QueryTransformOps;

/** Rewrites query syntax against a single graph view for its backing link. */
final class QueryGraphScope {
    private QueryGraphScope() { }

    static Query rewrite(Query query, Node graphName, Binding inputBinding) {
        if (query.hasDatasetDescription()) {
            throw new UnsupportedOperationException("FROM/FROM NAMED on a proxy graph view is not supported");
        }
        if (query.isDescribeType()) {
            // ARQ's DESCRIBE handlers subsequently access the local graph outside the plan.
            throw new UnsupportedOperationException("DESCRIBE on a proxy graph view is not supported; use CONSTRUCT");
        }
        if (graphName != null && !Quad.isDefaultGraph(graphName)
                && (!graphName.isURI() || Quad.isUnionGraph(graphName))) {
            throw new UnsupportedOperationException("Proxy graph views require a single graph IRI");
        }
        Query input = query.cloneQuery();
        if (input.isSelectType() && input.isQueryResultStar()) {
            // Note: A query may have an empty set of vars such as "SELECT * { }"
            List<String> vars = input.getResultVars();
            if (!vars.isEmpty()) {
                input.setQueryResultStar(false);
            }
        }
        if (inputBinding != null && !inputBinding.isEmpty()) {
            Map<Var, Node> substitutions = new HashMap<>();
            inputBinding.forEach(substitutions::put);
            List<Var> projection = List.copyOf(input.getProjectVars());
            input = QueryTransformOps.syntaxSubstitute(input, substitutions);
            // syntaxSubstitute also projects bindings absent from the original SELECT.
            for (Var var : List.copyOf(input.getProjectVars())) {
                if (!projection.contains(var)) {
                    input.getProject().remove(var);
                }
            }
        }
        // DatasetGraphOne exposes no named graphs. QueryTransformOps also applies
        // this transform to subqueries and expression patterns such as EXISTS.
        Query result = QueryTransformOps.transform(input, new ElementTransformCopyBase() {
            @Override
            public Element transform(ElementNamedGraph element, Node name, Element subElement) {
                if (Quad.isDefaultGraph(name)) {
                    return subElement;
                }
                ElementGroup empty = new ElementGroup();
                empty.addElement(new ElementFilter(NodeValue.FALSE));
                return empty;
            }

            @Override
            public Element transform(ElementService element, Node service, Element subElement) {
                // SERVICE has its own dataset. Keep its original body, including
                // nested GRAPH and EXISTS, rather than the transformed children.
                return element;
            }
        });
        // FROM selects the remote graph without the empty-pattern/existence
        // side effects of wrapping the entire query pattern in GRAPH.
        if (graphName != null && !Quad.isDefaultGraph(graphName)) {
            result.addGraphURI(graphName.getURI());
        }
        return result;
    }
}
