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

import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.util.Context;
import org.apache.jena.sparql.util.Symbol;

/** Dataset-level proxy options, with request settings taking precedence. */
public final class ProxyDatasetSettings {
    /**
     * Enable query rewriting of default graph access into named-graph union access. Default false.
     */
    public static final Symbol UNION_DEFAULT_GRAPH =
            Symbol.create("https://w3id.org/aksw/jena/dataset#unionDefaultGraph");

    private ProxyDatasetSettings() {}

    /**
     * A separate request context; does not mutate either source.
     *
     * @param dataset the dataset whose context provides the base settings
     * @param request the request context whose settings take precedence
     * @return a merged copy of the dataset and request contexts
     */
    public static Context requestContext(DatasetGraph dataset, Context request) {
        return Context.mergeCopy(dataset.getContext(), request);
    }

    /**
     * Read a flag, with an explicitly supplied request value overriding its dataset default.
     *
     * @param dataset the dataset whose context provides the default value
     * @param request the request context whose value takes precedence when it is defined
     * @return whether union-default-graph rewriting is enabled
     */
    public static boolean unionDefaultGraph(DatasetGraph dataset, Context request) {
        Context settings =
                request != null && request.isDefined(UNION_DEFAULT_GRAPH) ? request : dataset.getContext();
        return settings.isTrue(UNION_DEFAULT_GRAPH);
    }
}
