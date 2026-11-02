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

import org.aksw.jena.proxy.algebra.udg.TransformUnionDefaultGraph;
import org.aksw.jena.rdflink.dataset.ProxyDatasetSettings;
import org.aksw.jena.rdflink.dataset.QueryEngineFactoryProxyGraphs;
import org.apache.jena.assembler.Assembler;
import org.apache.jena.assembler.exceptions.AssemblerException;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.assembler.AssemblerUtils;
import org.apache.jena.sparql.core.assembler.DatasetAssemblerGeneral;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.util.Symbol;

/**
 * Assembles an ordinary map-link dataset using ja:defaultGraph/ja:namedGraph and context options.
 */
public class DatasetAssemblerProxy extends DatasetAssemblerGeneral {
    private static boolean initialized;

    /** Create the assembler. */
    public DatasetAssemblerProxy() {}

    /** Register with Jena's standard assembler group. */
    public static synchronized void init() {
        if (!initialized) {
            AssemblerUtils.registerAssembler(
                    Assembler.general(), VocabAssemblerProxy.tDatasetProxy, new DatasetAssemblerProxy());
            initialized = true;
        }
    }

    @Override
    public DatasetGraph createDataset(Assembler assembler, Resource root) {
        DatasetGraph dataset = super.createDataset(assembler, root);
        // Explicit convenience properties override generic ja:context settings.
        apply(
                root,
                VocabAssemblerProxy.pProxyGraphsEnabled,
                QueryEngineFactoryProxyGraphs.ENABLED,
                dataset);
        apply(
                root,
                VocabAssemblerProxy.pUnionDefaultGraph,
                ProxyDatasetSettings.UNION_DEFAULT_GRAPH,
                dataset);
        apply(root, VocabAssemblerProxy.pUdgStrict, TransformUnionDefaultGraph.STRICT, dataset);
        apply(
                root, VocabAssemblerProxy.pUdgDeduplicate, TransformUnionDefaultGraph.DEDUPLICATE, dataset);
        return dataset;
    }

    private static void apply(Resource root, Property property, Symbol symbol, DatasetGraph dataset) {
        var values = root.listProperties(property).toList();
        if (values.isEmpty()) return;
        if (values.size() != 1)
            throw new AssemblerException(root, "Expected one boolean value for " + property);
        NodeValue value = NodeValue.makeNode(values.get(0).getObject().asNode());
        if (!value.isBoolean())
            throw new AssemblerException(root, "Expected a boolean for " + property);
        dataset.getContext().set(symbol, value.getBoolean());
    }
}
