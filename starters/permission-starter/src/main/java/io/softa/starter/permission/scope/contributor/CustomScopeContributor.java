package io.softa.starter.permission.scope.contributor;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

import io.softa.framework.orm.domain.Filters;
import io.softa.starter.permission.scope.DepartmentSubtreeFilterRewriter;
import io.softa.starter.permission.scope.ScopeEnvGuard;
import io.softa.starter.permission.spi.ScopeRule;
import io.softa.starter.permission.spi.ScopeType;
import io.softa.starter.permission.spi.ScopeContributor;

/**
 * {@link ScopeType#CUSTOM} — admin-authored {@link Filters} JSON. Same
 * tuple-array shape as a {@code FlexQuery.filters}, produced by the FE
 * wizard's {@code FilterDialog}. Deserialized straight through
 * {@link Filters#of(String)} so operator semantics are identical to
 * runtime user-typed filters.
 *
 * <h3>Dynamic values — resolved at SQL-build time, not pre-compiled</h3>
 * The stored array JSON is the source of truth as-authored — this
 * contributor does <b>not</b> rewrite it. A leaf VALUE left as an env
 * placeholder ({@code USER_ID} / {@code USER_EMP_ID} / {@code USER_DEPT_ID}
 * / {@code USER_COMP_ID} / {@code USER_POSITION_ID} / {@code NOW} /
 * {@code TODAY} / …, see {@code EnvConstant.ENV_PARAMS}) is substituted with
 * the caller's context value by {@code FilterUnitParser} when the SQL is
 * built — it reads {@code ContextHolder.getContext().getUserId()} /
 * {@code getEmpInfo()}, the same variable resolution ordinary user queries
 * use. So this contributor just deserializes and returns the {@link Filters}.
 *
 * <p>Fail-closed: a null / non-array / empty / unparseable expression yields
 * {@code new Filters()} (contributes "no rows" to the OR-merge).
 */
@Slf4j
@Component
public class CustomScopeContributor implements ScopeContributor {

    /**
     * A CUSTOM rule is authored in the same dialog as a runtime filter, so it can name
     * {@code CHILD OF} on a department reference too — and it needs the same rewrite onto
     * {@code idPath}. It cannot ride on the one in {@code PermissionServiceImpl}: that runs on the
     * caller's filters, and this rule is compiled afterwards and AND-ed on, so it would never be
     * seen there.
     */
    private final DepartmentSubtreeFilterRewriter subtreeRewriter;

    public CustomScopeContributor(DepartmentSubtreeFilterRewriter subtreeRewriter) {
        this.subtreeRewriter = subtreeRewriter;
    }

    @Override
    public ScopeType scopeType() {
        return ScopeType.CUSTOM;
    }



    @Override
    public Filters compile(ScopeRule rule, String modelName) {
        JsonNode expr = rule.getScopeExpr();
        if (expr == null || !expr.isArray() || expr.isEmpty()) return new Filters();
        try {
            Filters parsed = Filters.of(expr.toString());
            if (parsed == null) {
                return new Filters();
            }
            // Fail closed when the caller lacks what a placeholder in the rule resolves against
            // (a pure user has no EmpInfo): drop the rule so it contributes "no rows", instead of
            // letting FilterUnitParser throw at SQL-build time. Same guard the identity scopes use.
            if (!ScopeEnvGuard.contextSatisfies(parsed)) {
                return new Filters();
            }
            return subtreeRewriter.rewrite(modelName, parsed);
        } catch (Throwable t) {
            log.warn("CustomScope — failed to parse scopeExpr; degrading to empty", t);
            return new Filters();
        }
    }
}
