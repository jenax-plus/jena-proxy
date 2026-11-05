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

import static org.junit.jupiter.api.Assertions.*;

import org.apache.jena.sparql.core.*;
import org.apache.jena.sparql.util.*;
import org.junit.jupiter.api.*;

class TestProxyDatasetSettings {
    private static final Symbol STANDARD = Symbol.create("http://jena.apache.org/TDB#unionDefaultGraph");
    private static final Symbol LEGACY = Symbol.create("https://w3id.org/aksw/jena/dataset#unionDefaultGraph");

    @BeforeAll static void init() { org.apache.jena.sys.JenaSystem.init(); }

    @Test void accessorUsesStandardSymbol() {
        assertEquals(STANDARD, ProxyDatasetSettings.UNION_DEFAULT_GRAPH);
        Context context = new Context();
        assertFalse(ProxyDatasetSettings.isUnionDefaultGraph(context));
        ProxyDatasetSettings.setUnionDefaultGraph(context, true);
        assertTrue(context.isTrue(STANDARD));
        assertTrue(ProxyDatasetSettings.isUnionDefaultGraph(context));
        ProxyDatasetSettings.setUnionDefaultGraph(context, false);
        assertFalse(context.isTrue(STANDARD));
    }

    @Test void oldProxyFlagIsPublishedUnderStandardSymbol() {
        DatasetGraph dataset = DatasetGraphFactory.createGeneral();
        dataset.getContext().set(LEGACY, true);
        try {
            Context request = ProxyDatasetSettings.requestContext(dataset, null);
            assertTrue(request.isTrue(STANDARD));
            assertTrue(ProxyDatasetSettings.unionDefaultGraph(dataset, null));
            assertFalse(dataset.getContext().isDefined(STANDARD));
        } finally { dataset.close(); }
    }

    @Test void requestOverridesDatasetAcrossAliases() {
        for (Symbol datasetSymbol : new Symbol[]{STANDARD, LEGACY}) {
            for (Symbol requestSymbol : new Symbol[]{STANDARD, LEGACY}) {
                DatasetGraph dataset = DatasetGraphFactory.createGeneral();
                try {
                    for (boolean enabled : new boolean[]{false, true}) {
                        dataset.getContext().set(datasetSymbol, enabled);
                        Context request = new Context();
                        request.set(requestSymbol, !enabled);
                        assertEquals(!enabled, ProxyDatasetSettings.unionDefaultGraph(dataset, request));
                        Context merged = ProxyDatasetSettings.requestContext(dataset, request);
                        assertEquals(!enabled, merged.isTrue(STANDARD));
                        assertEquals(enabled, dataset.getContext().isTrue(datasetSymbol));
                        assertFalse(request.isDefined(requestSymbol.equals(STANDARD) ? LEGACY : STANDARD));
                    }
                } finally { dataset.close(); }
            }
        }
    }

    @Test void canonicalSettingWinsWithinOneContext() {
        Context context = new Context();
        context.set(LEGACY, true);
        context.set(STANDARD, false);
        assertFalse(ProxyDatasetSettings.isUnionDefaultGraph(context));
        ProxyDatasetSettings.setUnionDefaultGraph(context, true);
        assertFalse(context.isDefined(LEGACY));
        assertTrue(context.isTrue(STANDARD));
    }
}
