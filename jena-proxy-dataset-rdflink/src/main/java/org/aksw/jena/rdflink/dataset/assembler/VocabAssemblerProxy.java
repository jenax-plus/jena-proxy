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

package org.aksw.jena.rdflink.dataset.assembler;

import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.system.Vocab;

/** Convenience vocabulary for a map-link dataset with proxy/UDG context settings. */
public final class VocabAssemblerProxy {
    private static final String NS = VocabAssemblerHTTP.getURI();

    /** Ordinary graph-collection dataset with proxy context configuration. */
    public static final Resource tDatasetProxy = Vocab.type(NS, "DatasetProxy");

    /** Master opt-out for the composite proxy query engine. */
    public static final Property pProxyGraphsEnabled = Vocab.property(NS, "proxyGraphsEnabled");

    /** Enable union-default-graph rewriting; defaults to false. */
    public static final Property pUnionDefaultGraph = Vocab.property(NS, "unionDefaultGraph");

    /** Reject unsupported UDG operations; defaults to true. */
    public static final Property pUdgStrict = Vocab.property(NS, "udgStrict");

    /** Eliminate cross-graph triple duplicates; defaults to true. */
    public static final Property pUdgDeduplicate = Vocab.property(NS, "udgDeduplicate");

    private VocabAssemblerProxy() {}
}
