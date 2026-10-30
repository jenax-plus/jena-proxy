/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.aksw.jena.rdflink.dataset.assembler;

import java.net.Authenticator;
import java.net.http.HttpClient;
import java.util.Arrays;
import java.util.Set;

import org.aksw.jena.rdflink.dataset.DatasetGraphOverRDFLink;
import org.apache.jena.assembler.Assembler;
import org.apache.jena.assembler.assemblers.AssemblerGroup;
import org.apache.jena.assembler.exceptions.AssemblerException;
import org.apache.jena.atlas.lib.Creator;
import org.apache.jena.http.HttpEnv;
import org.apache.jena.http.auth.AuthLib;
import org.apache.jena.http.sys.HttpRequestModifier;
import org.apache.jena.http.sys.RegistryRequestModifier;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdflink.RDFLink;
import org.apache.jena.rdflink.RDFLinkHTTP;
import org.apache.jena.rdflink.RDFLinkHTTPBuilder;
import org.apache.jena.riot.web.HttpNames;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.assembler.AssemblerUtils;
import org.apache.jena.sparql.core.assembler.DatasetAssembler;
import org.apache.jena.sparql.exec.http.QuerySendMode;
import org.apache.jena.sparql.exec.http.UpdateSendMode;
import org.apache.jena.sparql.util.graph.GraphUtils;
import org.apache.jena.sys.JenaSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Assembler for creating {@link DatasetGraph} from HTTP endpoints.
 *
 * @since 0.7.0
 */
public class DatasetAssemblerHTTP extends DatasetAssembler
{
    private static final Logger logger = LoggerFactory.getLogger(DatasetAssemblerHTTP.class);

    // Showing the user agent warning once should be sufficient.
    private static boolean warningShown_userAgent = false;

    /**
     * Utility classes should not be instantiated.
     */
    private DatasetAssemblerHTTP() {
    }

    static { JenaSystem.init(); }


    private static boolean initialized = false;

    static { init(); }

    /**
     * Initialize the assembler by registering it with the default assembler group.
     */
    static public synchronized void init() {
        if ( initialized )
            return;
        registerWith(Assembler.general());
        initialized = true;
    }

    /**
     * Register this assembler with the given assembler group.
     *
     * @param g the assembler group
     */
    static void registerWith(AssemblerGroup g) {
        // Wire in the assemblers.
        AssemblerUtils.registerAssembler(g, VocabAssemblerHTTP.tDatasetHTTP, new DatasetAssemblerHTTP());
    }

    @Override
    public DatasetGraph createDataset(Assembler a, Resource root) {
        return make(a, root);
    }

    /**
     * Get a property value as a string, or return a default.
     *
     * @param r the resource
     * @param p the property
     * @param dft the default value
     * @return the property value or the default
     */
    private static String getAsString(Resource r, Property p, String dft) {
        String tmp = GraphUtils.getAsStringValue(r, p);
        return (tmp != null) ? tmp : dft;
    }

    private static Boolean getAsBoolean(Resource r, Property p) {
        Statement s = r.getProperty(p);
        if (s == null)
            return null;
        return s.getBoolean();
    }

    /**
     * Create a dataset from an assembler resource configuration.
     *
     * @param a the assembler
     * @param root the root resource
     * @return the created dataset graph
     */
    public static DatasetGraph make(Assembler a, Resource root) {
        // Use destination as the default that can be overridden by  specific endpoints.
        String destination = GraphUtils.getAsStringValue(root, VocabAssemblerHTTP.pDestination);

        String queryEndpoint = destination;
        String updateEndpoint = destination;
        String gspEndpoint = destination;

        queryEndpoint  = getAsString(root, VocabAssemblerHTTP.pQueryEndpoint, destination);
        updateEndpoint = getAsString(root, VocabAssemblerHTTP.pUpdateEndpoint, destination);
        gspEndpoint    = getAsString(root, VocabAssemblerHTTP.pGspEndpoint, destination);

        String q = queryEndpoint;
        String u = updateEndpoint;
        String g = gspEndpoint;

        if (q == null && u == null && g == null) {
            throw new AssemblerException(root, "No destination set using any of the properties: " +
                Arrays.asList(VocabAssemblerHTTP.pDestination, VocabAssemblerHTTP.pQueryEndpoint, VocabAssemblerHTTP.pUpdateEndpoint, VocabAssemblerHTTP.pGspEndpoint));
        }

        String userAgent = GraphUtils.getStringValue(root, VocabAssemblerHTTP.pUserAgent);
        if (userAgent != null) {
            if (!warningShown_userAgent) {
                warningShown_userAgent = true;
                logger.warn("Due to technical limitations, HTTP User-Agents headers are registered globally on a URL basis.");
                logger.warn("Beware that multiple configurations for the same URL may interfere.");
            }
            RegistryRequestModifier reg = RegistryRequestModifier.get();
            Set<String> endpoints = Set.of(queryEndpoint, updateEndpoint, gspEndpoint);
            for (String endpoint : endpoints) {
                logger.info("Registering HTTP User-Agent for service " + endpoint + ": " + userAgent);
                HttpRequestModifier mod = (params, headers) -> headers.put(HttpNames.hUserAgent, userAgent);
                reg.add(endpoint, mod);
            }
        }

        boolean isBasicAuth = false;
        boolean isBearerAuth = false;
        String user = null;
        String pass = null;
        String token = null;

        HttpClient httpClient = null;

        Resource authConf = GraphUtils.getResourceValue(root, VocabAssemblerHTTP.pAuth);
        if (authConf != null) {
            user = GraphUtils.getStringValue(authConf, VocabAssemblerHTTP.pUser);
            pass = GraphUtils.getStringValue(authConf, VocabAssemblerHTTP.pPass);
            token = GraphUtils.getStringValue(authConf, VocabAssemblerHTTP.pToken);

            isBasicAuth = user != null || pass != null;
            isBearerAuth = token != null;
        }

        if (isBasicAuth && isBearerAuth) {
            throw new AssemblerException(root, "HTTP Auth: Multiple methods specified.");
        }

        if (isBasicAuth) {
            if ((user != null && pass == null)) {
                throw new AssemblerException(root, "HTTP Credentials: Password is null.");
            }

            if ((user == null && pass != null)) {
                throw new AssemblerException(root, "HTTP Credentials: User is null.");
            }

            if (user != null || pass != null) {
                Authenticator auth = AuthLib.authenticator(user, pass);
                httpClient = HttpEnv.httpClientBuilder().authenticator(auth).build();
            }
        }

        if (isBearerAuth) {
            throw new UnsupportedOperationException("Bearer auth not yet implemented.");
        }

        HttpClient h = httpClient;

        Creator<RDFLink> linkCreator = () -> {
            RDFLinkHTTPBuilder builder = RDFLinkHTTP.newBuilder()
                .queryEndpoint(q)
                .updateEndpoint(u)
                .gspEndpoint(g)
                .httpClient(h);

            // Accept headers
            String acceptSelect = GraphUtils.getAsStringValue(root, VocabAssemblerHTTP.pAcceptSelectQuery);
            String acceptAsk = GraphUtils.getAsStringValue(root, VocabAssemblerHTTP.pAcceptAskQuery);
            String acceptGraph = GraphUtils.getAsStringValue(root, VocabAssemblerHTTP.pAcceptGraph);
            String acceptDataset = GraphUtils.getAsStringValue(root, VocabAssemblerHTTP.pAcceptDataset);
            String acceptQuery = GraphUtils.getAsStringValue(root, VocabAssemblerHTTP.pAcceptQuery);

            if (acceptSelect != null)
                builder.acceptHeaderSelectQuery(acceptSelect);
            if (acceptAsk != null)
                builder.acceptHeaderAskQuery(acceptAsk);
            if (acceptGraph != null)
                builder.acceptHeaderGraph(acceptGraph);
            if (acceptDataset != null)
                builder.acceptHeaderDataset(acceptDataset);
            if (acceptQuery != null)
                builder.acceptHeaderQuery(acceptQuery);

            // Output formats
            String quadsFormat = GraphUtils.getAsStringValue(root, VocabAssemblerHTTP.pQuadsFormat);
            String triplesFormat = GraphUtils.getAsStringValue(root, VocabAssemblerHTTP.pTriplesFormat);

            if (quadsFormat != null)
                builder.quadsFormat(quadsFormat);
            if (triplesFormat != null)
                builder.triplesFormat(triplesFormat);

            // Send modes
            String querySendMode = GraphUtils.getAsStringValue(root, VocabAssemblerHTTP.pQuerySendMode);
            String updateSendMode = GraphUtils.getAsStringValue(root, VocabAssemblerHTTP.pUpdateSendMode);

            if (querySendMode != null)
                builder.querySendMode(QuerySendMode.valueOf(querySendMode));
            if (updateSendMode != null)
                builder.updateSendMode(UpdateSendMode.valueOf(updateSendMode));

            // Parse checks
            Boolean parseCheck = getAsBoolean(root, VocabAssemblerHTTP.pParseCheckSPARQL);
            if (parseCheck != null)
                builder.parseCheckSPARQL(parseCheck);

            RDFLink link = builder.build();
            return link;
        };

        DatasetGraph dsg = DatasetGraphOverRDFLink.create(linkCreator);
        AssemblerUtils.mergeContext(root, dsg.getContext());
        return dsg;
    }
}
