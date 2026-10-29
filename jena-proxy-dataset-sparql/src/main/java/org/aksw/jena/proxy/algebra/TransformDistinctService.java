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
 * Delegate DISTINCT, including its projection, to a caller-approved fixed service.
 * The caller certifies independent evaluation and compatible duplicate semantics.
 * Variable services, SILENT services and non-approved routes are left alone.
 */
public class TransformDistinctService extends TransformCopy {
    private final Predicate<OpService> servicePolicy;

    /** Select eligible service boundaries without rewriting their existing bodies. */
    public TransformDistinctService(Predicate<OpService> servicePolicy) {
        this.servicePolicy = servicePolicy;
    }

    @Override
    public Op transform(OpDistinct distinct, Op subOp) {
        Op input = subOp;
        OpProject projection = null;
        if (input instanceof OpProject project) {
            // SPARQL SELECT has no zero-column projection. Keep that algebra
            // operation local rather than serializing it as SELECT *.
            if (project.getVars().isEmpty()) return super.transform(distinct, subOp);
            projection = project;
            input = project.getSubOp();
        }
        if (input instanceof OpService service && service.getService().isURI()
                && !service.getSilent() && servicePolicy.test(service)) {
            Op remote = service.getSubOp();
            if (projection != null) remote = new OpProject(remote, projection.getVars());
            return new OpService(service.getService(), OpDistinct.create(remote), false);
        }
        return super.transform(distinct, subOp);
    }
}
