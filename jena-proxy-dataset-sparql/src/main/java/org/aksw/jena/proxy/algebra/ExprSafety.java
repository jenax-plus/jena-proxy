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

import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.walker.Walker;
import org.apache.jena.sparql.expr.*;

/** Conservative expression contracts for rules that change evaluation placement or cardinality. */
public final class ExprSafety {
    private ExprSafety() {}

    /**
     * Stable built-in expressions with explicit argument dependencies only.
     * ExprLib.isStable alone does not certify registry-backed functions or dynamic
     * calls. EXISTS/NOT EXISTS require graph-pattern/correlation analysis and are
     * conservatively barriers here, even when their surface expressions look stable.
     */
    public static boolean isStableAndTransparent(Expr expr) {
        if (!ExprLib.isStable(expr)) return false;
        if (expr instanceof E_Function || expr instanceof E_Call || expr instanceof ExprFunctionOp) return false;
        if (expr.isFunction()) {
            return expr.getFunction().getArgs().stream().allMatch(ExprSafety::isStableAndTransparent);
        }
        return expr.isVariable() || expr.isConstant();
    }

    /** Check an operand before moving bindings across its expressions. */
    public static boolean hasOnlyStableTransparentExpressions(Op op) {
        AtomicBoolean safe = new AtomicBoolean(true);
        ExprVisitor visitor = new ExprVisitorBase() {
            private void check(Expr expr) { if (!isStableAndTransparent(expr)) safe.set(false); }
            @Override public void visit(ExprFunction0 expr) { check(expr); }
            @Override public void visit(ExprFunction1 expr) { check(expr); }
            @Override public void visit(ExprFunction2 expr) { check(expr); }
            @Override public void visit(ExprFunction3 expr) { check(expr); }
            @Override public void visit(ExprFunctionN expr) { check(expr); }
            @Override public void visit(ExprFunctionOp expr) { check(expr); }
        };
        Walker.walk(op, null, visitor);
        return safe.get();
    }
}
