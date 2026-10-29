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

package org.aksw.jena.proxy.algebra;

import java.util.function.Predicate;
import org.apache.jena.sparql.algebra.*;
import org.apache.jena.sparql.algebra.op.*;

/**
 * Delegate a complete group to an eligible fixed, non-SILENT service. The caller certifies endpoint
 * capabilities, expression semantics and that the service is evaluated independently of incoming
 * bindings. No registration is automatic.
 */
public class TransformGroupService extends TransformCopy {
    private final Predicate<OpService> servicePolicy;
    private final Predicate<OpGroup> groupPolicy;

    /**
     * Explicit eligibility policies for service routes and aggregate/expression capabilities.
     *
     * @param servicePolicy selects the service routes eligible for delegation
     * @param groupPolicy selects the groups whose aggregates the service can evaluate
     */
    public TransformGroupService(Predicate<OpService> servicePolicy, Predicate<OpGroup> groupPolicy) {
        this.servicePolicy = servicePolicy;
        this.groupPolicy = groupPolicy;
    }

    @Override
    public Op transform(OpGroup group, Op subOp) {
        if (subOp instanceof OpService service
                && service.getService().isURI()
                && !service.getSilent()
                && servicePolicy.test(service)
                && groupPolicy.test(group)) {
            Op remote = new OpGroup(service.getSubOp(), group.getGroupVars(), group.getAggregators());
            // Discard the old syntax element: it describes the pre-rewrite body.
            return new OpService(service.getService(), remote, false);
        }
        return super.transform(group, subOp);
    }
}
