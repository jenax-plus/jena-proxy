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

import java.util.List;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.op.*;
import org.apache.jena.sparql.algebra.optimize.TransformSimplify;

/** Remove exact join identities without discarding projection/execution scope boundaries. */
public class TransformNormalizeJoinIdentities extends TransformSimplify {
    /** Create the stateless normalization transform. */
    public TransformNormalizeJoinIdentities() {}

    @Override
    public Op transform(OpSequence sequence, List<Op> members) {
        List<Op> remaining = members.stream().filter(op -> !OpJoin.isJoinIdentify(op)).toList();
        if (remaining.isEmpty()) return OpTable.unit();
        if (remaining.size() == 1) return remaining.get(0);
        return super.transform(sequence, remaining);
    }
}
