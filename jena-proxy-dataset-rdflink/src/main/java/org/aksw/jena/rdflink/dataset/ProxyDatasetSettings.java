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
     * Enable default-graph union rewriting using the TDB2-compatible symbol. Default false.
     */
    public static final Symbol UNION_DEFAULT_GRAPH =
            Symbol.create("http://jena.apache.org/TDB#unionDefaultGraph");

    private static final Symbol LEGACY_UNION_DEFAULT_GRAPH =
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
        Context result = Context.mergeCopy(dataset.getContext(), request);
        if (hasUnionDefaultGraphSetting(request) || hasUnionDefaultGraphSetting(dataset.getContext())) {
            // Publish one standard flag for consumers such as SERVICE extensions.
            setUnionDefaultGraph(result, unionDefaultGraph(dataset, request));
        }
        return result;
    }

    /**
     * Read a flag, with an explicitly supplied request value overriding its dataset default.
     *
     * @param dataset the dataset whose context provides the default value
     * @param request the request context whose value takes precedence when it is defined
     * @return whether union-default-graph rewriting is enabled
     */
    public static boolean unionDefaultGraph(DatasetGraph dataset, Context request) {
        Context settings = hasUnionDefaultGraphSetting(request) ? request : dataset.getContext();
        return isUnionDefaultGraph(settings);
    }

    private static boolean hasUnionDefaultGraphSetting(Context context) {
        return context != null && (context.isDefined(UNION_DEFAULT_GRAPH)
                || context.isDefined(LEGACY_UNION_DEFAULT_GRAPH));
    }

    /** Read UDG mode, accepting the former proxy symbol for compatibility. */
    public static boolean isUnionDefaultGraph(Context context) {
        if (context == null) return false;
        return context.isDefined(UNION_DEFAULT_GRAPH)
                ? context.isTrue(UNION_DEFAULT_GRAPH)
                : context.isTrue(LEGACY_UNION_DEFAULT_GRAPH);
    }

    /** Set UDG mode using Jena's TDB2-compatible symbol. */
    public static void setUnionDefaultGraph(Context context, boolean enabled) {
        context.set(UNION_DEFAULT_GRAPH, enabled);
        context.remove(LEGACY_UNION_DEFAULT_GRAPH);
    }
}
