package io.softa.starter.permission.scope;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

import io.softa.framework.base.constant.EnvConstant;
import io.softa.framework.orm.domain.FilterUnit;
import io.softa.framework.orm.domain.Filters;
import io.softa.starter.permission.entity.DataScopeType;

/**
 * Shared helpers for the {@link DataScopeType} filter templates (identity scopes),
 * used by both sides of the same row (2026-07-17 filter-merge):
 *
 * <ul>
 *   <li>{@link #toFilters(Object)} — parse a raw template value (JsonNode / List /
 *       JSON string, depending on how the 约定读 Map deserialises the JSON column)
 *       into a fresh {@link Filters}, leaving env placeholders intact
 *       ({@link IdentityScopeCompiler} — compile side);</li>
 *   <li>{@link #fieldRefs(Object)} — the LHS field names the template references, for
 *       applicability ({@link ScopeApplicabilityResolver} — enforce/authoring side);</li>
 *   <li>{@link #envTokens(Filters)} — the {@link EnvConstant} placeholder tokens used
 *       as leaf values, for the compiler's fail-closed guard.</li>
 * </ul>
 */
@Slf4j
final class ScopeFilterTemplates {

    private ScopeFilterTemplates() {}

    /** Build a fresh {@link Filters} from a raw template value, or {@code null} when
     *  blank / unparseable. Env placeholders in leaf values are left intact so
     *  {@code FilterUnitParser} can resolve them at SQL-build time. */
    static Filters toFilters(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            if (raw instanceof JsonNode jn) {
                return jn.isEmpty() ? null : Filters.of(jn.toString());
            }
            if (raw instanceof List<?> list) {
                return list.isEmpty() ? null : Filters.of(list);
            }
            if (raw instanceof CharSequence cs) {
                String s = cs.toString().trim();
                return s.isEmpty() ? null : Filters.of(s);
            }
        } catch (RuntimeException e) {
            log.warn("ScopeFilterTemplates — bad filter template {}; ignoring", raw, e);
        }
        return null;
    }

    /** LHS field names the template references (never the RHS env values) — used to
     *  decide which models the type applies to. */
    static Set<String> fieldRefs(Object raw) {
        Filters f = toFilters(raw);
        if (f == null) {
            return Set.of();
        }
        Set<String> out = new HashSet<>();
        collectFields(f, out);
        return out;
    }

    private static void collectFields(Filters f, Set<String> out) {
        if (f == null) {
            return;
        }
        FilterUnit u = f.getFilterUnit();
        if (u != null) {
            if (u.getField() != null && !u.getField().isBlank()) {
                out.add(u.getField());
            }
            if (u.getFields() != null) {
                out.addAll(u.getFields());
            }
        }
        for (Filters child : f.getChildren()) {
            collectFields(child, out);
        }
    }

    /** The {@link EnvConstant} placeholder tokens used as leaf values in the template
     *  (e.g. {@code USER_EMP_ID} / {@code USER_ID}). The compiler checks these against
     *  the bound context to fail closed before {@code FilterUnitParser} would throw on
     *  an EMP_INFO token with no {@code EmpInfo}. */
    static Set<String> envTokens(Filters f) {
        Set<String> out = new HashSet<>();
        collectEnvTokens(f, out);
        return out;
    }

    private static void collectEnvTokens(Filters f, Set<String> out) {
        if (f == null) {
            return;
        }
        FilterUnit u = f.getFilterUnit();
        if (u != null) {
            addEnvToken(u.getValue(), out);
        }
        for (Filters child : f.getChildren()) {
            collectEnvTokens(child, out);
        }
    }

    private static void addEnvToken(Object v, Set<String> out) {
        if (v instanceof CharSequence cs) {
            String token = cs.toString().toUpperCase();
            if (EnvConstant.ENV_PARAMS.contains(token)) {
                out.add(token);
            }
        } else if (v instanceof Collection<?> col) {
            for (Object o : col) {
                addEnvToken(o, out);
            }
        }
    }
}
