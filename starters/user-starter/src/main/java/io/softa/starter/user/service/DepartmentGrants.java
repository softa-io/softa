package io.softa.starter.user.service;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

import tools.jackson.databind.JsonNode;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.scope.DepartmentGrantRules;
import io.softa.framework.orm.service.ModelService;

/**
 * The role's department grant ({@code Role.departmentGrant}) as the authoring side reads it: what a
 * stored value means, what a save may write, which models it bounds, and where the granted
 * departments sit in the tree.
 *
 * <p>What a stored value says and which models it bounds is {@link DepartmentGrantRules}, which the
 * enforcing side in permission-starter reads too; what is here is what only the authoring side needs.
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

    static final String TYPE_ALL = DepartmentGrantRules.TYPE_ALL;
    static final String TYPE_SUBTREE = DepartmentGrantRules.TYPE_SUBTREE;
    static final String ROOT_KEY = DepartmentGrantRules.ROOT_KEY;
    static final String TYPE_OWN_DEPARTMENT = DepartmentGrantRules.TYPE_OWN_DEPARTMENT;
    static final String TYPE_MANAGED_DEPARTMENTS = DepartmentGrantRules.TYPE_MANAGED_DEPARTMENTS;
    static final String TYPE_DIRECT_REPORTS = DepartmentGrantRules.TYPE_DIRECT_REPORTS;

    /** What separates the segments of the department model's materialized path. */
    private static final String SEPARATOR = "/";

    /** The role-data-scope field exempting a card from the grant. */
    private static final String EXEMPT_FIELD = "departmentGrantExempt";

    private DepartmentGrants() {
    }

    /** Whether the role model declares the grant at all; an application that has not added it cannot write one. */
    public static boolean declared() {
        return ModelManager.getModelFieldOrNull("Role", FIELD) != null;
    }

    /** Whether the role data scope model declares the exemption; one that has not added it cannot read it. */
    public static boolean exemptDeclared() {
        return ModelManager.getModelFieldOrNull("RoleDataScope", EXEMPT_FIELD) != null;
    }

    /** No grant configured: null, or a JSON null. */
    public static boolean isUnconfigured(@Nullable JsonNode grant) {
        return DepartmentGrantRules.isUnconfigured(grant);
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
            String name = DepartmentGrantRules.typeOf(rule);
            if (TYPE_ALL.equals(name)) {
                all = true;
            } else if ((name == null || !DepartmentGrantRules.HOLDER_CONDITIONS.contains(name))
                    && (!TYPE_SUBTREE.equals(name) || DepartmentGrantRules.rootOf(rule) == null)) {
                throw new BusinessException("A department grant rule is all departments, one department and "
                        + "everything under it, the holder's own department, the departments they manage, or "
                        + "their direct reports.");
            }
        }
        if (all && grant.size() > 1) {
            throw new BusinessException("A department grant of all departments cannot also name departments.");
        }
    }

    /** See {@link DepartmentGrantRules#roots}. */
    public static @Nullable Set<Long> roots(@Nullable JsonNode grant) {
        return DepartmentGrantRules.roots(grant);
    }

    /** See {@link DepartmentGrantRules#holderConditions}. */
    public static Set<String> holderConditions(@Nullable JsonNode grant) {
        return DepartmentGrantRules.holderConditions(grant);
    }

    /** Whether the department grant bounds {@code model}; see {@link DepartmentGrantRules#anchorOf}. */
    public static boolean bounds(String model) {
        return DepartmentGrantRules.anchorOf(model) != null;
    }

    /** See {@link DepartmentGrantRules#idPathsOf}. */
    public static Map<Long, String> idPathsOf(ModelService<?> modelService, Collection<Long> departmentIds) {
        return DepartmentGrantRules.idPathsOf(modelService, departmentIds);
    }

    /** Whether {@code path} is {@code ancestor} or under it — on whole segments, so {@code /1/12} is not under {@code /1/1}. */
    public static boolean within(String path, String ancestor) {
        return path.equals(ancestor) || path.startsWith(ancestor + SEPARATOR);
    }
}
