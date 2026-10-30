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

import java.util.Optional;

import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.util.Context;
import org.apache.jena.sparql.util.ContextAccumulator;
import org.apache.jena.sparql.util.Symbol;

/**
 * Helper methods to compute the effective value of the ARQ parse check option.
 *
 * @since 0.7.0
 */
public class ParseCheckUtils {
    // private static final Symbol parseCheck = ARQConstants.parseCheck;
    private static final Symbol parseCheck = Symbol.create("parseCheck");

    /**
     * Utility classes should not be instantiated.
     */
    private ParseCheckUtils() {
    }

    /**
     * Set the parse check option in a context.
     *
     * @param cxt the context
     * @param value the parse check value
     */
    public static void setParseCheck(Context cxt, Boolean value) {
        cxt.set(parseCheck, value);
    }

    /**
     * Get the parse check option from a dataset graph's context.
     *
     * @param dsg the dataset graph
     * @return the parse check value wrapped in an Optional
     */
    public static Optional<Boolean> getParseCheck(DatasetGraph dsg) {
        return Optional.ofNullable(dsg).map(DatasetGraph::getContext).flatMap(ParseCheckUtils::getParseCheck);
    }

    /**
     * Get the parse check option from a context.
     *
     * @param cxt the context
     * @return the parse check value wrapped in an Optional
     */
    public static Optional<Boolean> getParseCheck(Context cxt) {
        return Optional.ofNullable(cxt).map(c -> c.get(parseCheck));
    }

    /**
     * Get the parse check option from a context accumulator.
     * Currently returns empty as ContextAccumulator.get method is not yet available in Jena.
     *
     * @param cxtAcc the context accumulator
     * @return empty Optional
     */
    public static Optional<Boolean> getParseCheck(ContextAccumulator cxtAcc) {
        return Optional.empty();
        // FIXME The ContextAccumulator.get method did not yet make it into Jena. It should allow lookup-by-key without building the whole context.
        // return Optional.ofNullable(cxtAcc).map(ca -> ca.get(parseCheck);
    }

    /**
     * Compute the effective parse check value, using the provided value if present
     * or falling back to the context value.
     *
     * @param parseCheck the explicit parse check value
     * @param cxt the context to check if parseCheck is null
     * @return the effective parse check value
     */
    public static boolean effectiveParseCheck(Boolean parseCheck, Context cxt) {
        return Optional.ofNullable(parseCheck).orElseGet(() -> getParseCheck(cxt).orElse(true));
    }

    /**
     * Compute the effective parse check value from a context accumulator.
     *
     * @param parseCheck the explicit parse check value
     * @param cxtAcc the context accumulator
     * @return the effective parse check value
     */
    public static boolean effectiveParseCheck(Boolean parseCheck, ContextAccumulator cxtAcc) {
        return Optional.ofNullable(parseCheck).orElseGet(() -> getParseCheck(cxtAcc).orElse(true));
    }
}
