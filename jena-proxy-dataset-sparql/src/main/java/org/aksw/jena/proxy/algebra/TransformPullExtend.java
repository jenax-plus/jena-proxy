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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.OpVars;
import org.apache.jena.sparql.algebra.Transform;
import org.apache.jena.sparql.algebra.Transformer;
import org.apache.jena.sparql.algebra.op.OpDisjunction;
import org.apache.jena.sparql.algebra.op.OpDistinct;
import org.apache.jena.sparql.algebra.op.OpExtend;
import org.apache.jena.sparql.algebra.op.OpFilter;
import org.apache.jena.sparql.algebra.op.OpGroup;
import org.apache.jena.sparql.algebra.op.OpJoin;
import org.apache.jena.sparql.algebra.op.OpLateral;
import org.apache.jena.sparql.algebra.op.OpProject;
import org.apache.jena.sparql.algebra.op.OpSlice;
import org.apache.jena.sparql.algebra.op.OpUnion;
import org.apache.jena.sparql.algebra.optimize.TransformExtendCombine;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.core.VarExprList;
import org.apache.jena.sparql.expr.E_GreaterThan;
import org.apache.jena.sparql.expr.Expr;
import org.apache.jena.sparql.expr.ExprAggregator;
import org.apache.jena.sparql.expr.ExprList;
import org.apache.jena.sparql.expr.ExprVar;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.expr.aggregate.AggCount;
import org.apache.jena.sparql.expr.aggregate.AggCountDistinct;

/**
 * Self-contained adaptation of Claus Stadler's Jenax TransformPullExtend and ProjectExtend, with
 * constant/group handling for aggregation pushdown. Pulls extensions across unions, joins, filters,
 * slices, DISTINCT and groups. Constants differing between union branches remain on their
 * respective branches.
 *
 * @see <a
 *     href="https://github.com/Scaseco/jenax/blob/develop/jenax-arq-parent/jenax-arq-algebra/src/main/java/org/aksw/jena_sparql_api/algebra/transform/TransformPullExtend.java">Jenax
 *     original</a>
 */
public class TransformPullExtend extends TransformExtendCombine {
    private final RewriteVars freshVar;

    /**
     * Reserve temporary variables against the complete input algebra.
     *
     * @param root the algebra against which fresh variables are reserved
     */
    public TransformPullExtend(Op root) {
        freshVar = new RewriteVars(root);
    }

    /**
     * Run the pull-extend rewrite; existing SERVICE bodies remain opaque.
     *
     * @param op the algebra to rewrite
     * @return the rewritten algebra with extensions pulled
     */
    public static Op transform(Op op) {
        Transform transform = new TransformPullExtend(op);
        Op result = Transformer.transformSkipService(transform, op);
        return result;
    }

    @Override
    public Op transform(OpDisjunction opDisjunction, List<Op> elts) {
        // Op result = transformDisjunction(elts, list -> OpUnion.create(list.get(0), list.get(1)));
        // Alternative if disjunction should be retained.
        Op result = transformDisjunction(elts, list -> {
            OpDisjunction disjunction = OpDisjunction.create();
            list.forEach(disjunction::add);
            return disjunction;
        });
        if (result == null) {
            result = super.transform(opDisjunction, elts);
        }
        return result;
    }

    @Override
    public Op transform(OpUnion opUnion, Op left, Op right) {
        Op result = transformDisjunction(Arrays.asList(left, right), list -> OpUnion.create(list.get(0), list.get(1)));
        if (result == null) {
            result = super.transform(opUnion, left, right);
        }
        return result;
    }

    @Override
    public Op transform(OpProject opProject, Op subOp) {
        Op result;
        if (subOp instanceof OpProject) {
            OpProject o = (OpProject) subOp;
            Set<Var> vars = new HashSet<>(o.getVars());
            List<Var> newVars = opProject.getVars().stream().filter(vars::contains).collect(Collectors.toList());
            result = new OpProject(o.getSubOp(), newVars);
        } else {
            result = super.transform(opProject, subOp);
        }
        return result;
    }

    /**
     * Pull constant assignments shared by every branch above the disjunction.
     *
     * @param subOps the branch operations
     * @param toUnion rebuilds a disjunction-style operator from the rewritten branches
     * @return the result with common constants lifted, or null when nothing can be pulled
     */
    public Op transformDisjunction(List<Op> subOps, Function<List<Op>, Op> toUnion) {
        Op result = null; // null unless change
        VarExprList common = new VarExprList();
        List<ProjectExtend> pes = subOps.stream().map(ProjectExtend::collect).collect(Collectors.toList());
        boolean allMembersProcessable = !pes.stream().anyMatch(Objects::isNull);

        if (allMembersProcessable) {
            // The set of variables that are _candidates_ for pulling
            Set<Var> candidatePullableVars = pes.stream().map(ProjectExtend::getPullableVars).flatMap(Collection::stream).collect(Collectors.toSet());

            Set<Var> rejectedCandidateVars = new HashSet<>();
            // Iterator<Var> it = commonPullableVars.iterator();
            nextVar: for (Var var : candidatePullableVars) {
                boolean isPriorSet = false;
                Expr prior = null;
                for (ProjectExtend pe : pes) {
                    // An expression can be present but hidden by this branch's
                    // projection, or needed by assignments that must remain below.
                    if (!pe.getPullableVars().contains(var)) {
                        rejectedCandidateVars.add(var);
                        continue nextVar;
                    }
                    Expr expr = pe.getVel().getExpr(var);
                    if (!isPriorSet) {
                        isPriorSet = true;
                        prior = expr;
                    } else if (!Objects.equals(prior, expr)) {
                        rejectedCandidateVars.add(var);
                        continue nextVar;
                    }
                }
                if (prior != null) {
                    common.add(var, prior);
                }
            }

            // Note: We need to add non-pullable vars back
            if (!common.isEmpty()) {
                List<Op> newOps = new ArrayList<>(subOps.size());
                for (ProjectExtend pe : pes) {
                    List<Var> nonPullableVars = new ArrayList<>(pe.getNonPullableVars());
                    // Restore only this branch's rejected outputs. Adding every
                    // rejected variable can expose another branch's hidden binding.
                    pe.getPullableVars().stream().filter(v -> !common.contains(v)).forEach(nonPullableVars::add);

                    VarExprList nonPullableVel = VarExprListUtils.projectAllVars(pe.getVel(), nonPullableVars);
                    Op newInnerOp = ProjectExtend.applyIfNeeded(pe.getProject() != null, nonPullableVel, pe.getSubOp());
                    // Op newDistinct = OpDistinct.create(newInnerOp);
                    newOps.add(newInnerOp);
                }

                Op newDisjunction = toUnion.apply(newOps);

                VarExprList pullable = VarExprListUtils.projectAllVars(common, common.getVars());
                result = ProjectExtend.applyIfNeeded(false, pullable, newDisjunction);
            }
        }

        return result;
    }

    @Override
    public Op transform(OpLateral op, Op left, Op right) {
        // Probably we don't need to check for 'join' vars because the rhs is probably prohibited to
        // BIND visibles variables that are in-scope on the lhs
        // Set<Var> vvLeft = OpVars.visibleVars(left);
        // Set<Var> vvRight = OpVars.visibleVars(right);
        // However, we must not pull variables from the rhs that are defined in terms of lhs ones

        // ProjectExtend peLeft = ProjectExtend.collect(left);
        ProjectExtend peRight = ProjectExtend.collect(right);

        Op result = null;
        if (peRight != null && !peRight.getPullableVars().isEmpty()
                && Collections.disjoint(peRight.getPullableVars(), OpVars.mentionedVars(left))) {
            VarExprList nonPullable = VarExprListUtils.projectAllVars(peRight.getVel(), peRight.getNonPullableVars());
            Op newInnerRight = ProjectExtend.applyIfNeeded(peRight.getProject() != null, nonPullable, peRight.getSubOp());

            // Special case If the innerRight is merely OpExtend(unit) then we can omit the lateral
            // X LATERAL { BIND(?bar) BIND(?baz) } becomes X BIND(?bar) BIND(?baz)
            if (newInnerRight instanceof OpExtend) {
                OpExtend o = (OpExtend) newInnerRight;
                if (OpJoin.isJoinIdentify(o.getSubOp())) {
                    Op newLateral = OpExtend.create(left, o.getVarExprList());
                    VarExprList pullable = VarExprListUtils.projectAllVars(peRight.getVel(), peRight.getPullableVars());
                    result = restoreLateralOutputs(left, peRight, pullable, newLateral);
                }
            }

            if (result == null) {
                Op newLateral = OpLateral.create(left, newInnerRight);
                VarExprList pullable = VarExprListUtils.projectAllVars(peRight.getVel(), peRight.getPullableVars());
                result = restoreLateralOutputs(left, peRight, pullable, newLateral);
            }
        } else {
            result = super.transform(op, left, right);
        }
        return result;
    }

    @Override
    public Op transform(OpSlice op, Op subOp) {
        ProjectExtend pe = ProjectExtend.collect(subOp);
        Op result;
        if (pe != null && pe.getVel().getExprs().values().stream().allMatch(ExprSafety::isStableAndTransparent)) {
            result = pe.apply(new OpSlice(pe.getSubOp(), op.getStart(), op.getLength()));
        } else {
            result = super.transform(op, subOp);
        }
        return result;
    }

    @Override
    public Op transform(OpDistinct op, Op subOp) {
        Op result;
        ProjectExtend pe = ProjectExtend.collect(subOp);

        if (pe != null && !pe.getPullableVars().isEmpty()) {
            VarExprList nonPullable = VarExprListUtils.projectAllVars(pe.getVel(), pe.getNonPullableVars());
            Op newInnerOp = ProjectExtend.applyIfNeeded(pe.getProject() != null, nonPullable, pe.getSubOp());
            Op newDistinct = OpDistinct.create(newInnerOp);
            VarExprList pullable = VarExprListUtils.projectAllVars(pe.getVel(), pe.getPullableVars());
            result = ProjectExtend.apply(pe.getProject(), pullable, newDistinct);
        } else {
            result = super.transform(op, subOp);
        }
        return result;
    }

    // Filter(Extend(X, e), f) -> Extend(Filter(X, f), e) if filter does not depend on any variable
    // of e
    @Override
    public Op transform(OpFilter opFilter, Op subOp) {
        Op result = null;

        // TODO We can do alot better here - this is old code that does not yet use ProjectExtend

        if (subOp instanceof OpExtend) {
            OpExtend e = (OpExtend) subOp;
            if (!e.getVarExprList().getExprs().values().stream().allMatch(ExprSafety::isStableAndTransparent)
                    || !opFilter.getExprs().getList().stream().allMatch(ExprSafety::isStableAndTransparent)) {
                return super.transform(opFilter, subOp);
            }

            VarExprList vel = e.getVarExprList();
            Set<Var> extendVars = VarExprListUtils.getVarsMentioned(vel);

            ExprList el = opFilter.getExprs();
            Set<Var> usedVars = el.getVarsMentioned();

            Set<Var> conflicts = intersection(extendVars, usedVars);
            if (conflicts.isEmpty()) {
                boolean containsSpecialVars =
                        containsSpecialVar(extendVars) || containsSpecialVar(usedVars);

                if (!containsSpecialVars) {
                    result = OpExtend.create(OpFilter.filterBy(el, e.getSubOp()), vel);
                }
            }
        }

        if (result == null) {
            result = super.transform(opFilter, subOp);
        }

        return result;
    }

    // TODO Astra rewrite the join logic as this - is this equivalvent?
    @Override
    public Op transform(OpJoin join, Op left, Op right) {
        VarExprList lifted = new VarExprList();
        Op newLeft = left;
        Op newRight = right;
        if (left instanceof OpExtend extend && canPullJoin(extend, right)) {
            lifted.addAll(extend.getVarExprList());
            newLeft = extend.getSubOp();
        }
        if (right instanceof OpExtend extend && canPullJoin(extend, left)) {
            lifted.addAll(extend.getVarExprList());
            newRight = extend.getSubOp();
        }
        return lifted.isEmpty()
                ? super.transform(join, left, right)
                : OpExtend.create(OpJoin.create(newLeft, newRight), lifted);
    }

    private static boolean canPullJoin(OpExtend extend, Op other) {
        VarExprList vel = extend.getVarExprList();
        return vel.getExprs().values().stream().allMatch(Expr::isConstant)
                && ExprSafety.hasOnlyStableTransparentExpressions(other)
                && java.util.Collections.disjoint(vel.getVars(), OpVars.mentionedVars(other))
                && java.util.Collections.disjoint(vel.getVars(), OpVars.visibleVars(extend.getSubOp()));
    }

    // TODO The original code fails the new join test case - but why?
    /*
    @Override
    public Op transform(OpJoin opJoin, Op left, Op right) {
        OpExtend ol = left instanceof OpExtend ? ((OpExtend)left) : null;
        OpExtend or = right instanceof OpExtend ? ((OpExtend)right) : null;
        VarExprList velLeft = ol != null ? ol.getVarExprList() : null;
        VarExprList velRight = or != null ? or.getVarExprList() : null;

        Op result;
        if(velLeft != null && velRight != null) {
            Set<Var> conflicts = intersection(velLeft.getVars(), velRight.getVars());
            if(conflicts.isEmpty()) {
                VarExprList combined = new VarExprList();
                combined.addAll(velLeft);
                combined.addAll(velRight);

                result = OpExtend.extend(OpJoin.create(ol.getSubOp(), or.getSubOp()), combined);
            } else {
                // TODO We could pull up all non conflicting binds
                // Also, we could even create a filter FALSE for conflicting vars
                // But for now we don't bother
                result = super.transform(opJoin, left, right);
            }
        } else if(velLeft != null) {
            result = OpExtend.extend(OpJoin.create(ol.getSubOp(), right), velLeft);
        } else if(velRight != null) {
            result = OpExtend.extend(OpJoin.create(left, or.getSubOp()), velRight);
        } else {
            result = super.transform(opJoin, left, right);
        }

        return result;
    }
    */

    @Override
    public Op transform(OpGroup group, Op subOp) {
        if (!(subOp instanceof OpExtend extend) || !group.getGroupVars().getExprs().isEmpty()) {
            return super.transform(group, subOp);
        }
        VarExprList assignments = extend.getVarExprList();
        Set<Var> assigned = new HashSet<>(assignments.getVars());
        if (assignments.getExprs().values().stream().anyMatch(e -> !e.isConstant())
                || !Collections.disjoint(assigned, OpVars.visibleVars(extend.getSubOp()))) {
            return super.transform(group, subOp);
        }
        for (ExprAggregator agg : group.getAggregators()) {
            if (agg.getAggregator().getExprList() != null && !agg.getAggregator().getExprList().getList().stream()
                    .allMatch(ExprSafety::isStableAndTransparent)) {
                return super.transform(group, subOp);
            }
            if (agg.getAggregator() instanceof org.apache.jena.sparql.expr.aggregate.AggCustom) {
                return super.transform(group, subOp);
            }
            if (agg.getAggregator().getExprList() != null
                    && !Collections.disjoint(
                            assigned, agg.getAggregator().getExprList().getVarsMentioned())) {
                return super.transform(group, subOp);
            }
            // COUNT(DISTINCT *) observes entire mappings; conservatively decline
            // aggregates with implicit access to more than their explicit arguments.
            if (agg.getAggregator() instanceof AggCountDistinct) {
                return super.transform(group, subOp);
            }
        }
        VarExprList keys = new VarExprList();
        VarExprList restore = new VarExprList();
        for (Var key : group.getGroupVars().getVars()) {
            if (assigned.contains(key)) {
                restore.add(key, assignments.getExpr(key));
            } else {
                keys.add(key);
            }
        }
        List<ExprAggregator> aggs = new ArrayList<>(group.getAggregators());
        Var witness = null;
        if (keys.isEmpty() && !group.getGroupVars().isEmpty()) {
            // GROUP BY a constant produces no group on empty input. An implicit
            // global group does produce a row, so retain evidence of input rows.
            witness = freshVar.get();
            aggs.add(new ExprAggregator(witness, new AggCount()));
        }
        Op result = new OpGroup(extend.getSubOp(), keys, aggs);
        if (witness != null) {
            result = OpFilter.filter(new E_GreaterThan(new ExprVar(witness), NodeValue.nvZERO), result);
        }
        if (!restore.isEmpty()) {
            result = OpExtend.create(result, restore);
        }
        List<Var> outputs = new ArrayList<>(group.getGroupVars().getVars());
        group.getAggregators().forEach(a -> outputs.add(a.getVar()));
        return new OpProject(result, outputs);
    }

    private static Set<Var> intersection(Collection<Var> left, Collection<Var> right) {
        Set<Var> result = new LinkedHashSet<>(left);
        result.retainAll(right);
        return result;
    }

    private static boolean containsSpecialVar(Collection<Var> vars) {
        return vars.stream().anyMatch(v -> Var.isAllocVar(v) || Var.isAnonVar(v));
    }

    private static class VarExprListUtils {
        static VarExprList projectAllVars(VarExprList vel, Collection<Var> vars) {
            VarExprList result = new VarExprList();
            for (Var var : vars) {
                Expr expr = vel.getExpr(var);
                if (expr == null) result.add(var);
                else result.add(var, expr);
            }
            return result;
        }

        static VarExprList projectExprsOnly(VarExprList vel, Collection<Var> vars) {
            VarExprList result = new VarExprList();
            for (Var var : vars) {
                Expr expr = vel.getExpr(var);
                if (expr != null) result.add(var, expr);
            }
            return result;
        }

        static Set<Var> getVarsMentioned(VarExprList vel) {
            Set<Var> result = new HashSet<>(vel.getVars());
            vel.getExprs().values().forEach(e -> result.addAll(e.getVarsMentioned()));
            return result;
        }
    }

    private static class ProjectExtend {
        protected List<Var> project = null;
        protected VarExprList vel = null;
        protected Op subOp;

        protected Set<Var> pullableVars = new LinkedHashSet<>();
        protected Set<Var> nonPullableVars;

        public ProjectExtend(List<Var> project, VarExprList vel, Op subOp) {
            super();
            this.project = project;
            this.vel = vel;
            this.subOp = subOp;

            Collection<Var> vars = project != null ? project : vel.getVars();
            for (Var var : vars) {
                Expr expr = vel.getExpr(var);
                if (expr != null && expr.isConstant() && !OpVars.visibleVars(subOp).contains(var)) {
                    pullableVars.add(var);
                }
            }

            // Opaque functions can inspect the entire binding, not just their
            // declared arguments. Do not remove constants from their environment.
            if (!vel.getExprs().values().stream().allMatch(ExprSafety::isStableAndTransparent)) {
                pullableVars.clear();
            }
            // An assignment that remains below still needs its input constants.
            for (Expr expr : vel.getExprs().values()) {
                pullableVars.removeAll(expr.getVarsMentioned());
            }
            nonPullableVars = vars.stream()
                .filter(v -> !pullableVars.contains(v))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        public Set<Var> getPullableVars() {
            return pullableVars;
        }

        public Set<Var> getNonPullableVars() {
            return nonPullableVars;
        }

        public List<Var> getProject() {
            return project;
        }

        public VarExprList getVel() {
            return vel;
        }

        public Op getSubOp() {
            return subOp;
        }

        public Op toOp() {
            return apply(subOp);
        }

        public Op apply(Op subOp) {
            Op result = subOp;
            if (vel != null) {
                result = OpExtend.create(result, vel);
            }

            if (project != null) {
                result = new OpProject(result, project);
            }
            return result;
        }

        public static Op apply(List<Var> project, VarExprList vel, Op subOp) {
            Op result = subOp;
            if (vel != null) {
                result = OpExtend.create(result, vel);
            }

            if (project != null) {
                result = new OpProject(result, project);
            }
            return result;
        }

        /**
         * @param project If true, inject a OpProject with the variables in vel.
         * @param vel
         * @param subOp
         * @return
         */
        public static Op applyIfNeeded(boolean project, VarExprList vel, Op subOp) {
            Op result = subOp;
            if (!vel.isEmpty()) {
                VarExprList tmp = VarExprListUtils.projectExprsOnly(vel, vel.getVars());
                if (!tmp.isEmpty()) {
                    result = OpExtend.create(result, tmp);
                }
            }
            if (project) {
                result = new OpProject(result, vel.getVars());
            }
            return result;
        }

        public static ProjectExtend collect(Op op) {
            List<Var> project = null;
            VarExprList vel = null;
            Op tmp = op;
            if (tmp instanceof OpProject) {
                OpProject o = (OpProject) tmp;
                project = o.getVars();
                tmp = o.getSubOp();
            }

            if (tmp instanceof OpExtend) {
                OpExtend o = (OpExtend) tmp;
                vel = o.getVarExprList();
                tmp = o.getSubOp();
            }

            ProjectExtend result = vel == null ? null : new ProjectExtend(project, vel, tmp);
            return result;
        }

        @Override
        public String toString() {
            String result = "" + apply(subOp);
            return result;
        }
    }

    /** A RHS projection belongs to that operand, not to the complete lateral result. */
    private static Op restoreLateralOutputs(Op left, ProjectExtend right, VarExprList lifted, Op op) {
        Op result = ProjectExtend.applyIfNeeded(false, lifted, op);
        if (right.getProject() != null) {
            Set<Var> outputs = new LinkedHashSet<>(OpVars.visibleVars(left));
            outputs.addAll(right.getProject());
            result = new OpProject(result, new ArrayList<>(outputs));
        }
        return result;
    }
}
