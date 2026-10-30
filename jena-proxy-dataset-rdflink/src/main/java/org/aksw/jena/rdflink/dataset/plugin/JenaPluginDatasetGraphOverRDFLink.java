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

package org.aksw.jena.rdflink.dataset.plugin;

import org.aksw.jena.rdflink.dataset.DatasetGraphOverRDFLink;
import org.aksw.jena.rdflink.dataset.QueryEngineFactoryDatasetGraphOverRDFLink;
import org.aksw.jena.rdflink.dataset.QueryEngineFactoryProxyGraphs;
import org.aksw.jena.rdflink.dataset.assembler.DatasetAssemblerHTTP;
import org.apache.jena.sparql.engine.QueryEngineRegistry;
import org.apache.jena.sparql.modify.UpdateEngineRegistry;
import org.apache.jena.sys.JenaSubsystemLifecycle;

/**
 * Plugin that registers a query and update engine for {@link DatasetGraphOverRDFLink}.
 *
 * @since 0.7.0
 */
public class JenaPluginDatasetGraphOverRDFLink
    implements JenaSubsystemLifecycle
{
    /**
     * Called by JenaSubsystemLifecycle.
     */
    public JenaPluginDatasetGraphOverRDFLink() {
    }

    @Override
    public void start() {
        DatasetAssemblerHTTP.init();

        QueryEngineRegistry queryReg = QueryEngineRegistry.get();
        init(queryReg);

        UpdateEngineRegistry updateReg = UpdateEngineRegistry.get();
        init(updateReg);
    }

    @Override
    public void stop() {
    }

    /**
     * Initialize the plugin with a query engine registry.
     *
     * @param reg the query engine registry
     */
    public static void init(QueryEngineRegistry reg) {
        reg.add(new QueryEngineFactoryDatasetGraphOverRDFLink());
        reg.add(new QueryEngineFactoryProxyGraphs());
    }

    /**
     * Initialize the plugin with an update engine registry.
     * Currently a no-op as the update engine factory is not yet implemented.
     *
     * @param reg the update engine registry
     */
    public static void init(UpdateEngineRegistry reg) {
         // reg.add(new UpdateEngineFactoryover());
    }

    @Override
    public int level() {
        return 40;
    }
}
