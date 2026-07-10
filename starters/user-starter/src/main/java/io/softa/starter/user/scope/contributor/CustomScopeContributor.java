package io.softa.starter.user.scope.contributor;

import java.util.List;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

import io.softa.framework.orm.domain.Filters;
import io.softa.starter.user.dto.ScopeRule;
import io.softa.starter.user.enums.ScopeType;
import io.softa.starter.user.scope.ScopeContributor;

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

    @Override
    public ScopeType scopeType() {
        return ScopeType.CUSTOM;
    }

    @Override
    public List<String> applicableFields() {
        // Empty on purpose — CUSTOM is universally applicable. The default
        // isApplicableTo check would treat empty as "never applicable", so
        // we override the check below.
        return List.of();
    }

    @Override
    public boolean isApplicableTo(String modelName, java.util.Set<String> modelFieldNames) {
        // CUSTOM lets admins author any Filters expression — applicable to
        // every model; the filter itself is validated at compile time.
        return true;
    }

    @Override
    public Filters compile(ScopeRule rule, String modelName) {
        JsonNode expr = rule.getScopeExpr();
        if (expr == null || !expr.isArray() || expr.isEmpty()) return new Filters();
        try {
            Filters parsed = Filters.of(expr.toString());
            return parsed == null ? new Filters() : parsed;
        } catch (Throwable t) {
            log.warn("CustomScope — failed to parse scopeExpr; degrading to empty", t);
            return new Filters();
        }
    }
}
