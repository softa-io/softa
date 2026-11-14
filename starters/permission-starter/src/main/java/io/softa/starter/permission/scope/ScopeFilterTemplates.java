package io.softa.starter.permission.scope;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

import io.softa.framework.base.constant.EnvConstant;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.FilterUnit;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
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

    /** The field through which a record belongs to an employee, on a model with no manager field of its own. */
    private static final String OWNER_FIELD = ModelConstant.EMPLOYEE_FIELD;

    /**
     * The template a type compiles to on a model with these fields, or {@code null} when it does not apply.
     * The one reading applicability and compilation share:
     * <ol>
     *   <li>the identity model itself → its own template;</li>
     *   <li>a model carrying the fields the type names → those;</li>
     *   <li>a model carrying the identity model's own anchor, such as its manager field, → that;</li>
     *   <li>a model whose records belong to an employee → that employee's anchor, through {@code employeeId}.</li>
     * </ol>
     * The last two do not apply to a type whose identity template anchors on {@code id}: every model has one,
     * and it would say nothing about this model's rows.
     */
    static Filters forModel(String model, Set<String> fields, String identityModel, Object filter, Object identityFilter) {
        if (identityModel != null && identityModel.equals(model)) {
            return toFilters(identityFilter);
        }
        Set<String> own = fieldRefs(filter);
        if (!own.isEmpty() && fields.containsAll(own)) {
            return toFilters(filter);
        }
        Set<String> anchors = identityModel == null ? Set.of() : fieldRefs(identityFilter);
        if (anchors.isEmpty() || anchors.contains(ModelConstant.ID)) {
            return null;
        }
        if (fields.containsAll(anchors)) {
            return toFilters(identityFilter);
        }
        if (!belongsTo(model, identityModel)) {
            return null;
        }
        Filters through = toFilters(identityFilter);
        prefixFields(through);
        return through;
    }

    private static boolean belongsTo(String model, String identityModel) {
        MetaField owner = ModelManager.getModelFieldOrNull(model, OWNER_FIELD);
        return owner != null && FieldType.TO_ONE_TYPES.contains(owner.getFieldType())
                && identityModel.equals(owner.getRelatedModel());
    }

    private static void prefixFields(Filters f) {
        if (f == null) {
            return;
        }
        FilterUnit u = f.getFilterUnit();
        if (u != null && u.getField() != null && !u.getField().isBlank()) {
            u.setField(OWNER_FIELD + "." + u.getField());
        }
        for (Filters child : f.getChildren()) {
            prefixFields(child);
        }
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
