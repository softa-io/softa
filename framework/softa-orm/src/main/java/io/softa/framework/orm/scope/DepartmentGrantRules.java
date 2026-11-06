package io.softa.framework.orm.scope;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

import tools.jackson.databind.JsonNode;

import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.FilterControl;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;

/**
 * What a role's department grant says, and which models it bounds — the one reading shared by the side that
 * authors a grant and the side that enforces it.
 *
 * <p>The value is a JSON array of rules in the data-scope form: {@code ALL}; {@code DEPT_SUBTREE} with a fixed
 * {@code deptId}; or a condition worked out for whoever holds the role. A null is no grant at all.
 */
public final class DepartmentGrantRules {

    public static final String TYPE_ALL = "ALL";
    public static final String TYPE_SUBTREE = "DEPT_SUBTREE";
    public static final String TYPE_OWN_DEPARTMENT = "OWN_DEPARTMENT";
    public static final String TYPE_MANAGED_DEPARTMENTS = "MANAGED_DEPARTMENTS";
    public static final String TYPE_DIRECT_REPORTS = "DIRECT_REPORTS";

    /** The key of a subtree rule's fixed root. */
    public static final String ROOT_KEY = "deptId";

    /** The conditions worked out for the holder, as opposed to departments named once for everyone. */
    public static final Set<String> HOLDER_CONDITIONS =
            Set.of(TYPE_OWN_DEPARTMENT, TYPE_MANAGED_DEPARTMENTS, TYPE_DIRECT_REPORTS);

    /** The materialized-path field of the department model. */
    public static final String ID_PATH = "idPath";

    private DepartmentGrantRules() {
    }

    /** No grant configured: null, or a JSON null. */
    public static boolean isUnconfigured(@Nullable JsonNode grant) {
        return grant == null || grant.isNull() || grant.isMissingNode();
    }

    /**
     * The granted roots, or {@code null} when the grant does not bound by department — not configured, or
     * {@code ALL}. Rules that are not a subtree with a root contribute nothing, so a malformed grant reads
     * as reaching less, never more.
     */
    public static @Nullable Set<Long> roots(@Nullable JsonNode grant) {
        if (isUnconfigured(grant)) {
            return null;
        }
        Set<Long> roots = new LinkedHashSet<>();
        if (!grant.isArray()) {
            return roots;
        }
        for (JsonNode rule : grant) {
            String type = typeOf(rule);
            if (TYPE_ALL.equals(type)) {
                return null;
            }
            Long root = TYPE_SUBTREE.equals(type) ? rootOf(rule) : null;
            if (root != null) {
                roots.add(root);
            }
        }
        return roots;
    }

    /** The grant's conditions worked out for the holder; none when it names no such rule. */
    public static Set<String> holderConditions(@Nullable JsonNode grant) {
        Set<String> conditions = new LinkedHashSet<>();
        if (grant == null || !grant.isArray()) {
            return conditions;
        }
        for (JsonNode rule : grant) {
            String type = typeOf(rule);
            if (type != null && HOLDER_CONDITIONS.contains(type)) {
                conditions.add(type);
            }
        }
        return conditions;
    }

    /** A rule's type, or null when it has none. */
    public static @Nullable String typeOf(@Nullable JsonNode rule) {
        JsonNode type = rule == null ? null : rule.get("scopeType");
        return type != null && type.isString() ? type.asString() : null;
    }

    /** A subtree rule's fixed root, as a string (how clients send ids) or a number; null when absent. */
    public static @Nullable Long rootOf(JsonNode rule) {
        JsonNode expr = rule.get("scopeExpr");
        JsonNode root = expr == null || !expr.isObject() ? null : expr.get(ROOT_KEY);
        if (root == null) {
            return null;
        }
        if (root.isIntegralNumber()) {
            return root.asLong();
        }
        if (root.isString()) {
            try {
                return Long.valueOf(root.asString().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * The field a model is bounded by the grant through, or null when the grant does not reach it: the
     * department model by its own {@code id}; any other model by {@code departmentId}, when that field is a
     * to-one onto the department model. A {@code departmentId} that is a plain number column, or that points
     * elsewhere, is not an anchor: the grant would compare ids across models. A model that references
     * departments as configuration rather than ownership names the field otherwise, and so is not bounded.
     */
    public static @Nullable String anchorOf(@Nullable String model) {
        if (model == null || !ModelManager.existModel(model)) {
            return null;
        }
        if (ModelConstant.DEPARTMENT_MODEL.equals(model)) {
            return ModelConstant.ID;
        }
        MetaField field = ModelManager.getModelFieldOrNull(model, ModelConstant.DEPARTMENT_FIELD);
        boolean anchored = field != null
                && FieldType.TO_ONE_TYPES.contains(field.getFieldType())
                && ModelConstant.DEPARTMENT_MODEL.equals(field.getRelatedModel());
        return anchored ? ModelConstant.DEPARTMENT_FIELD : null;
    }

    /**
     * The stored {@code idPath} of each of these departments, disabled ones included: a disabled department
     * is still somewhere in the tree, and a grant naming it still reaches what is under it. A department that
     * no longer exists, or an application without the tree, contributes nothing. The caller decides the
     * context it reads in.
     */
    public static Map<Long, String> idPathsOf(ModelService<?> modelService, Collection<Long> departmentIds) {
        if (departmentIds.isEmpty() || !ModelManager.existModel(ModelConstant.DEPARTMENT_MODEL)
                || ModelManager.getModelFieldOrNull(ModelConstant.DEPARTMENT_MODEL, ID_PATH) == null) {
            return Map.of();
        }
        Map<Long, String> paths = new HashMap<>();
        FlexQuery query = new FlexQuery(List.of(ModelConstant.ID, ID_PATH), new Filters().in(ModelConstant.ID, departmentIds));
        query.setFilterControl(FilterControl.bypassActiveControl());
        for (Map<String, Object> row : modelService.searchList(ModelConstant.DEPARTMENT_MODEL, query)) {
            if (row.get(ModelConstant.ID) instanceof Number id && row.get(ID_PATH) instanceof CharSequence path
                    && !path.isEmpty()) {
                paths.put(id.longValue(), path.toString());
            }
        }
        return paths;
    }
}
