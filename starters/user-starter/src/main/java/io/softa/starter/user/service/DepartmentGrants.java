package io.softa.starter.user.service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

import tools.jackson.databind.JsonNode;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.FilterControl;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;

/**
 * The role's department grant ({@code Role.departmentGrant}) as the authoring side reads it: what a
 * stored value means, what a save may write, which models it bounds, and where the granted
 * departments sit in the tree.
 *
 * <p>The enforcing side reads the same column in permission-starter, which this module does not
 * depend on, so the wire spelling — the rule types and the {@code deptId} key — is written out in both
 * places and asserted by tests on each side, as {@code RoleController} does for {@code ALL}.
 *
 * <h3>The value</h3>
 * A JSON array of rules in the data-scope form:
 * <ul>
 *   <li>{@code null} (or absent) — not configured: the role is not bounded by department at all;</li>
 *   <li>{@code [{"scopeType":"ALL"}]} — every department, also unbounded;</li>
 *   <li>{@code [{"scopeType":"DEPT_SUBTREE","scopeExpr":{"deptId":"5"}}, …]} — those departments, each
 *       with everything under it;</li>
 *   <li>{@code [{"scopeType":"OWN_DEPARTMENT"}]}, {@code MANAGED_DEPARTMENTS}, {@code DIRECT_REPORTS} —
 *       worked out for whoever holds the role, and combinable with the above: a record has to meet any
 *       one of them;</li>
 *   <li>{@code []} — configured to reach no department.</li>
 * </ul>
 */
public final class DepartmentGrants {

    /** The role field holding the grant. */
    public static final String FIELD = "departmentGrant";

    static final String TYPE_ALL = "ALL";
    static final String TYPE_SUBTREE = "DEPT_SUBTREE";
    static final String ROOT_KEY = "deptId";
    static final String TYPE_OWN_DEPARTMENT = "OWN_DEPARTMENT";
    static final String TYPE_MANAGED_DEPARTMENTS = "MANAGED_DEPARTMENTS";
    static final String TYPE_DIRECT_REPORTS = "DIRECT_REPORTS";

    /** The conditions worked out for the holder, as opposed to departments named once for everyone. */
    private static final Set<String> HOLDER_CONDITIONS =
            Set.of(TYPE_OWN_DEPARTMENT, TYPE_MANAGED_DEPARTMENTS, TYPE_DIRECT_REPORTS);

    /** The materialized-path field of the department model, and what separates its segments. */
    private static final String ID_PATH = "idPath";
    private static final String SEPARATOR = "/";

    private DepartmentGrants() {
    }

    /** Whether the role model declares the grant at all; an application that has not added it cannot write one. */
    public static boolean declared() {
        return ModelManager.getModelFieldOrNull("Role", FIELD) != null;
    }

    /** No grant configured: null, or a JSON null. */
    public static boolean isUnconfigured(@Nullable JsonNode grant) {
        return grant == null || grant.isNull() || grant.isMissingNode();
    }

    /**
     * Refuse a value a save may not write. Accepted: an array of {@code ALL}, or of subtree rules with
     * a fixed numeric root and the conditions worked out for the holder, possibly empty. {@code ALL}
     * stands alone — beside anything else it would make that meaningless, and reads as a mistake rather
     * than an intention. A subtree rule without a root would read as the holder's own department, which
     * has a rule of its own; it is refused rather than left to mean two things.
     */
    public static void validate(JsonNode grant) {
        if (!grant.isArray()) {
            throw new BusinessException("The department grant must be a list of rules.");
        }
        boolean all = false;
        for (JsonNode rule : grant) {
            JsonNode type = rule == null ? null : rule.get("scopeType");
            String name = type != null && type.isString() ? type.asString() : null;
            if (TYPE_ALL.equals(name)) {
                all = true;
            } else if (!HOLDER_CONDITIONS.contains(name) && (!TYPE_SUBTREE.equals(name) || rootOf(rule) == null)) {
                throw new BusinessException("A department grant rule is all departments, one department and "
                        + "everything under it, the holder's own department, the departments they manage, or "
                        + "their direct reports.");
            }
        }
        if (all && grant.size() > 1) {
            throw new BusinessException("A department grant of all departments cannot also name departments.");
        }
    }

    /**
     * The granted roots, or {@code null} when the grant does not bound by department — not configured,
     * or {@code ALL}. Rules that are not a subtree with a root contribute nothing, as on the enforcing
     * side, so such a grant reads as reaching less, never more.
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
            JsonNode type = rule == null ? null : rule.get("scopeType");
            if (type == null || !type.isString()) {
                continue;
            }
            if (TYPE_ALL.equals(type.asString())) {
                return null;
            }
            Long root = TYPE_SUBTREE.equals(type.asString()) ? rootOf(rule) : null;
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
            JsonNode type = rule == null ? null : rule.get("scopeType");
            if (type != null && type.isString() && HOLDER_CONDITIONS.contains(type.asString())) {
                conditions.add(type.asString());
            }
        }
        return conditions;
    }

    private static @Nullable Long rootOf(JsonNode rule) {
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
     * Whether the department grant bounds {@code model}: the department model itself, or a model whose
     * {@code departmentId} is a to-one onto it. The same reading the enforcing side makes.
     */
    public static boolean bounds(String model) {
        if (model == null || !ModelManager.existModel(model)) {
            return false;
        }
        if (ModelConstant.DEPARTMENT_MODEL.equals(model)) {
            return true;
        }
        MetaField field = ModelManager.getModelFieldOrNull(model, ModelConstant.DEPARTMENT_FIELD);
        return field != null
                && FieldType.TO_ONE_TYPES.contains(field.getFieldType())
                && ModelConstant.DEPARTMENT_MODEL.equals(field.getRelatedModel());
    }

    /**
     * The stored {@code idPath} of each of these departments, disabled ones included — a disabled department
     * is still somewhere in the tree, and a grant naming it still reaches what is under it. A department that
     * no longer exists, or an application without the tree, contributes nothing. The caller decides the
     * context it reads in.
     */
    public static Map<Long, String> idPathsOf(ModelService<?> modelService, Collection<Long> departmentIds) {
        if (departmentIds.isEmpty() || !ModelManager.existModel(ModelConstant.DEPARTMENT_MODEL)
                || ModelManager.getModelFieldOrNull(ModelConstant.DEPARTMENT_MODEL, ID_PATH) == null) {
            return Map.of();
        }
        Map<Long, String> paths = new java.util.HashMap<>();
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

    /** Whether {@code path} is {@code ancestor} or under it — on whole segments, so {@code /1/12} is not under {@code /1/1}. */
    public static boolean within(String path, String ancestor) {
        return path.equals(ancestor) || path.startsWith(ancestor + SEPARATOR);
    }
}
