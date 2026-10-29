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

/**
 * Reusable, opt-in ARQ algebra rewrites. This package depends only on Jena and the JDK; it has no
 * knowledge of graph routing, RDFLink, endpoint inventories or proxy execution. It can be extracted
 * into a standalone algebra module.
 *
 * <p>Suggested planning phases:
 *
 * <ol>
 *   <li>Source selection produces bag unions of SERVICE branches.
 *   <li>{@link org.aksw.jena.proxy.algebra.TransformGroupUnion} splits eligible groups once.
 *   <li>{@link org.aksw.jena.proxy.algebra.TransformGroupTable} normalizes singleton branch
 *       bindings.
 *   <li>{@link org.aksw.jena.proxy.algebra.TransformPullExtend} exposes groups below branch
 *       constants.
 *   <li>{@link org.aksw.jena.proxy.algebra.TransformGroupService} delegates eligible groups.
 * </ol>
 *
 * <p>DISTINCT placement and union distribution are separate rules. The appended
 * {@code TransformDistinctPlacement.rewrite(op, distributeOverUnion)} entry point
 * enables distribution explicitly; its one-argument form performs placement only.
 * {@link org.aksw.jena.proxy.algebra.TransformDistinctService} delegates eligible
 * branch-level distincts after placement.
 *
 * <p>After standard optimization, {@link org.aksw.jena.proxy.algebra.TransformOrderUnion}
 * distributes ORDER into sorted branches and {@link org.aksw.jena.proxy.algebra.TransformOrderService}
 * delegates eligible branch sorts. {@link org.aksw.jena.proxy.algebra.OpMergeSorted} performs
 * the sequential merge; execution extensions must be installed after ordinary algebra transforms.
 *
 * <p>Use {@code Transformer.transformSkipService} to preserve existing remote bodies. Construct
 * variable-allocating transforms with the complete current algebra, including outer scopes. Retain
 * existing scope-renaming and engine optimization phases. These transformations are not installed
 * globally.
 */
package org.aksw.jena.proxy.algebra;
