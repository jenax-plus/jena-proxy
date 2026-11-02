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

import java.util.Objects;
import java.util.function.Predicate;
import org.apache.jena.sparql.algebra.*;
import org.apache.jena.sparql.algebra.op.*;

/**
 * Delegate a live-column projection to an approved fixed non-SILENT service with a simple
 * triple-pattern body. Retain the client projection boundary. Modifier-bearing bodies are
 * deliberately left alone: collapsing their projections can change DISTINCT/group semantics.
 */
public class TransformProjectService extends TransformCopy {
    private final Predicate<OpService> allowed;

    /**
     * Create a projection delegate for the caller-approved services.
     *
     * @param allowed service eligibility policy
     */
    public TransformProjectService(Predicate<OpService> allowed) {
        this.allowed = Objects.requireNonNull(allowed);
    }

    @Override
    public Op transform(OpProject project, Op child) {
        if (!project.getVars().isEmpty()
                && child instanceof OpService service
                && service.getService().isURI()
                && !service.getSilent()
                && allowed.test(service)
                && (service.getSubOp() instanceof OpBGP || service.getSubOp() instanceof OpTriple)) {
            return new OpProject(
                    new OpService(
                            service.getService(), new OpProject(service.getSubOp(), project.getVars()), false),
                    project.getVars());
        }
        return super.transform(project, child);
    }
}
