package io.softa.starter.user.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.enums.BuiltinRole;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.user.entity.Role;
import io.softa.starter.user.entity.RoleDataScope;
import io.softa.starter.user.entity.RoleNavigation;
import io.softa.starter.user.entity.RoleSensitiveFieldSet;
import io.softa.starter.user.entity.UserRoleRel;

import static io.softa.framework.base.context.ContextUtils.inSystemContext;
import static io.softa.framework.base.context.ContextUtils.inTenantContext;

/**
 * A role manager hands out only what they hold themselves.
 *
 * <p>Without this, managing roles is the same as holding every permission: whoever may save a role may
 * give it any page, any action and any row, and then assign it to themselves. Callers holding a
 * built-in admin role ({@code SUPER_ADMIN}, {@code TENANT_ADMIN}, {@code CONSULTANT}) already bypass
 * the permission checks, so the ceiling is theirs to set and does not apply to them; system paths
 * ({@code skipPermissionCheck}) are exempt too.
 *
 * <p>For everyone else, a role is within reach when, compared with the caller's own static grants:
 * <ul>
 *   <li>its navigations, permissions and sensitive field sets are all ones the caller holds;</li>
 *   <li>for every action it grants on a model, one of the caller's roles holds that same action and
 *       reaches at least the rows the role would — the role's scope rules on the model are among that
 *       caller role's, under the same additional condition, on a multi-company model within that caller
 *       role's companies, and on a model bounded by the department grant within that caller role's
 *       departments. One caller role has to cover the action and the rows together, because
 *       that is how they combine at read time: a role that may view everyone and another that may edit
 *       one department add up to editing that department, not editing everyone.</li>
 * </ul>
 * Rules are compared as written, not by what they resolve to. That is deliberately narrow — a rule the
 * caller holds is a rule they may pass on, anything else is refused — and it keeps the check free of
 * any judgement about which rule is wider than another.
 *
 * <p>The department grant is the one exception, compared by where the departments sit rather than by
 * how the rule is written: a role may be given any department inside one of the caller role's granted
 * subtrees, judged on the stored {@code idPath}. Comparing as written would let a caller granted a
 * division pass on exactly that division and none of the teams under it, which is the delegation the
 * grant exists for.
 */
@Component
public class RoleGrantCeiling {

    private static final String M_PERMISSION = "Permission";
    private static final String M_NAVIGATION = "Navigation";
    private static final String M_SENSITIVE_FIELD_SET = "SensitiveFieldSet";

    private final RoleService roleService;
    private final RoleNavigationService roleNavigationService;
    private final RoleDataScopeService roleDataScopeService;
    private final RoleSensitiveFieldSetService roleSensitiveFieldSetService;
    private final UserRoleRelService userRoleRelService;
    private final ModelService<?> modelService;

    public RoleGrantCeiling(@Lazy RoleService roleService,
                            @Lazy RoleNavigationService roleNavigationService,
                            @Lazy RoleDataScopeService roleDataScopeService,
                            @Lazy RoleSensitiveFieldSetService roleSensitiveFieldSetService,
                            @Lazy UserRoleRelService userRoleRelService,
                            ModelService<?> modelService) {
        this.roleService = roleService;
        this.roleNavigationService = roleNavigationService;
        this.roleDataScopeService = roleDataScopeService;
        this.roleSensitiveFieldSetService = roleSensitiveFieldSetService;
        this.userRoleRelService = userRoleRelService;
        this.modelService = modelService;
    }

    /** Whether the current caller is held to the ceiling at all. */
    public static boolean applies() {
        Context context = ContextHolder.getContext();
        if (context == null || context.isSkipPermissionCheck()) {
            return false;
        }
        return !BuiltinRole.anyHeldBy(context.getRoleCodes(),
                BuiltinRole.SUPER_ADMIN, BuiltinRole.TENANT_ADMIN, BuiltinRole.CONSULTANT);
    }

    /** Refuse unless the role's stored grants are within the caller's reach. */
    public void assertWithinCaller(Long roleId) {
        assertWithinCaller(roleId, null);
    }

    /**
     * Refuse unless the role's stored grants are within {@code callerBefore} — the caller's grants as they
     * stood before the save that is being judged.
     *
     * <p>A save that rewrites a role the caller holds is otherwise judged against itself: the caller's
     * grants, read after the write, already carry the role's new grants, and every grant covers itself.
     * Taken before the write, they hold the role as it was, so the role may not be widened past it.
     */
    public void assertWithinCaller(Long roleId, @Nullable List<Grants> callerBefore) {
        violation(roleId, callerBefore).ifPresent(reason -> {
            throw new BusinessException("You can only grant access you hold yourself: {0}", reason);
        });
    }

    /** The caller's grants as they stand now, for a save to be judged against later; null when no ceiling applies. */
    public @Nullable List<Grants> callerGrants() {
        if (!applies()) {
            return null;
        }
        Context context = ContextHolder.getContext();
        Long userId = context.getUserId();
        return inTenantContext(context.getTenantId(), () -> new ArrayList<>(load(callerRoleIds(userId)).values()));
    }

    /** Why the role is out of the caller's reach, or empty when it is within it (or no ceiling applies). */
    public Optional<String> violation(Long roleId) {
        Set<Long> cleared = CLEARED.get();
        if (roleId != null && cleared != null && cleared.contains(roleId)) {
            return Optional.empty();
        }
        return violation(roleId, null);
    }

    /** Roles already judged within the caller during the save now running, by the grants before it. */
    private static final ThreadLocal<Set<Long>> CLEARED = new ThreadLocal<>();

    /**
     * Run {@code action} — the save's own rewrite of the role's members — with the role taken as within
     * the caller. The wizard judged the role against the caller's grants from before the save; its members
     * are rewritten after, and by then the caller's own membership of the role is among those deleted, so
     * judging each bind again would hold the role to grants that no longer include it.
     */
    public static void withRoleCleared(Long roleId, Runnable action) {
        Set<Long> previous = CLEARED.get();
        Set<Long> cleared = previous == null ? new HashSet<>() : new HashSet<>(previous);
        cleared.add(roleId);
        CLEARED.set(cleared);
        try {
            action.run();
        } finally {
            if (previous == null) CLEARED.remove(); else CLEARED.set(previous);
        }
    }

    private Optional<String> violation(Long roleId, @Nullable List<Grants> callerBefore) {
        if (roleId == null || !applies()) {
            return Optional.empty();
        }
        Context context = ContextHolder.getContext();
        Long tenantId = context.getTenantId();
        Long userId = context.getUserId();
        return inTenantContext(tenantId, () -> {
            Grants target = load(List.of(roleId)).getOrDefault(roleId, Grants.empty());
            List<Grants> caller = callerBefore != null ? callerBefore
                    : new ArrayList<>(load(callerRoleIds(userId)).values());
            Set<String> permissions = new HashSet<>(target.permissions());
            caller.forEach(g -> permissions.addAll(g.permissions()));
            return firstViolation(target, caller, modelsOf(permissions), modelsOfSets(target.sensitiveFieldSets()),
                    model -> ModelManager.existModel(model) && ModelManager.getModel(model).isMultiCompany(),
                    DepartmentGrants::bounds)
                    .map(this::describe);
        });
    }

    /**
     * The violation in the words of the screen that set it: page, action and field set by their names, rows by
     * the model's label and what to do about them. The ids mean nothing to an administrator, and a scope that
     * reaches too far is one fault however many of the role's actions run into it.
     */
    private String describe(Violation v) {
        return switch (v.kind()) {
            case NAVIGATION -> "you do not have the " + quoted(nameOf(M_NAVIGATION, v.subject())) + " page";
            case PERMISSION -> "you do not have " + quoted(permissionLabel(v.subject()));
            case FIELD_SET -> "none of your roles shows the sensitive fields " + quoted(nameOf(M_SENSITIVE_FIELD_SET, v.subject()))
                    + " on every record this role would";
            case RECORDS -> {
                String model = modelLabel(v.subject());
                yield "this role would reach " + model + " records beyond your own. Narrow its " + model
                        + " scope to no more than you can see yourself";
            }
            case DEPARTMENTS -> "this role would reach " + modelLabel(v.subject()) + " records in departments "
                    + "beyond your own. Grant it only departments within those you are granted yourself";
        };
    }

    private static String quoted(String name) {
        return "\"" + name + "\"";
    }

    private static String modelLabel(String model) {
        String label = ModelManager.existModel(model) ? ModelManager.getModel(model).getLabel() : null;
        return label == null || label.isBlank() ? model : label;
    }

    /** {@code Employees › Linked Employee Summary}: the page, then the action on it. */
    private String permissionLabel(String permissionId) {
        Map<String, Object> row = rowOf(M_PERMISSION, permissionId, List.of("id", "name", "navigationId"));
        if (row == null) {
            return permissionId;
        }
        String name = row.get("name") == null ? permissionId : row.get("name").toString();
        Object navigationId = row.get("navigationId");
        return navigationId == null ? name : nameOf(M_NAVIGATION, navigationId.toString()) + " › " + name;
    }

    private String nameOf(String model, String id) {
        Map<String, Object> row = rowOf(model, id, List.of("id", "name"));
        return row == null || row.get("name") == null ? id : row.get("name").toString();
    }

    private @Nullable Map<String, Object> rowOf(String model, String id, List<String> fields) {
        return inSystemContext(() -> modelService.searchList(model, new FlexQuery(fields, new Filters().eq("id", id)))
                .stream().findFirst().orElse(null));
    }

    // ─────────────────────── the decision ───────────────────────

    /**
     * The first way {@code target} exceeds what {@code caller} holds, or empty.
     *
     * @param modelByPermission each permission's primary model (its navigation's), when it has one
     * @param modelsBySet       each sensitive field set's model and the models it is attached to — the rows
     *                          the set's own model follows (a bank account follows its employee)
     * @param multiCompany      whether rows of a model belong to one company
     */
    static Optional<Violation> firstViolation(Grants target, List<Grants> caller,
                                           Map<String, String> modelByPermission,
                                           Map<String, Set<String>> modelsBySet,
                                           Predicate<String> multiCompany) {
        return firstViolation(target, caller, modelByPermission, modelsBySet, multiCompany, model -> false);
    }

    /**
     * {@link #firstViolation(Grants, List, Map, Map, Predicate)}, with the department grant applied to the
     * models it bounds.
     *
     * @param departmentBound whether the department grant bounds a model's rows
     */
    static Optional<Violation> firstViolation(Grants target, List<Grants> caller,
                                           Map<String, String> modelByPermission,
                                           Map<String, Set<String>> modelsBySet,
                                           Predicate<String> multiCompany,
                                           Predicate<String> departmentBound) {
        Axes axes = new Axes(multiCompany, departmentBound);
        Set<String> navigations = union(caller, Grants::navigations);
        for (String navigation : target.navigations()) {
            if (!navigations.contains(navigation)) {
                return Optional.of(new Violation(Violation.Kind.NAVIGATION, navigation));
            }
        }
        Set<String> permissions = union(caller, Grants::permissions);
        for (String permission : target.permissions()) {
            if (!permissions.contains(permission)) {
                return Optional.of(new Violation(Violation.Kind.PERMISSION, permission));
            }
        }
        // A field set reaches the rows of the role granting it, so one caller role must hold the set and
        // reach the rows: a role seeing its own salary and a role seeing everyone without salaries do not
        // add up to granting everyone's salaries.
        for (String fieldSet : target.sensitiveFieldSets()) {
            Set<String> models = modelsBySet.getOrDefault(fieldSet, Set.of());
            boolean covered = caller.stream().anyMatch(c -> c.sensitiveFieldSets().contains(fieldSet)
                    && models.stream().allMatch(m -> reachesRows(c, target, m, axes)));
            if (!covered) {
                return Optional.of(new Violation(Violation.Kind.FIELD_SET, fieldSet));
            }
        }
        // Rows: per action, one caller role holding that action must reach the rows.
        Set<String> modelsWithAction = new HashSet<>();
        for (String permission : target.permissions()) {
            String model = modelByPermission.get(permission);
            if (model == null) {
                continue;
            }
            modelsWithAction.add(model);
            boolean covered = caller.stream().anyMatch(c -> c.permissions().contains(permission)
                    && reachesRows(c, target, model, axes));
            if (!covered) {
                return Optional.of(recordsViolation(caller, target, model, axes,
                        c -> c.permissions().contains(permission)));
            }
        }
        // Rules on a model no granted action names directly — a lookup the role reads through — still
        // decide rows, so some caller role has to reach them.
        for (String model : target.scopes().keySet()) {
            if (ModelConstant.COMPANY_MODEL.equals(model) || modelsWithAction.contains(model)) {
                continue;
            }
            boolean covered = caller.stream().anyMatch(c -> reachesRows(c, target, model, axes));
            if (!covered) {
                return Optional.of(recordsViolation(caller, target, model, axes, c -> true));
            }
        }
        return Optional.empty();
    }

    /** Which role-level grants bound a model's rows besides its rules. */
    private record Axes(Predicate<String> multiCompany, Predicate<String> departmentBound) {
        Axes withoutDepartments() {
            return new Axes(multiCompany, model -> false);
        }
    }

    /**
     * The rows of {@code model} are out of reach: named as a department fault when the department grant
     * is all that stands in the way — some caller role eligible for the action would reach the rows
     * but for its departments — so the message points at the grant rather than at the scope.
     */
    private static Violation recordsViolation(List<Grants> caller, Grants target, String model, Axes axes,
                                              Predicate<Grants> eligible) {
        boolean departmentsOnly = axes.departmentBound().test(model) && caller.stream()
                .anyMatch(c -> eligible.test(c) && reachesRows(c, target, model, axes.withoutDepartments()));
        return new Violation(departmentsOnly ? Violation.Kind.DEPARTMENTS : Violation.Kind.RECORDS, model);
    }

    /** Whether caller role {@code c} reaches every row {@code target} would on {@code model}. */
    private static boolean reachesRows(Grants c, Grants target, String model, Axes axes) {
        if (axes.multiCompany().test(model) && !coversCompanies(c, target)) {
            return false;
        }
        if (axes.departmentBound().test(model) && !coversDepartments(c, target)) {
            return false;
        }
        Scope mine = c.scopes().get(model);
        Scope theirs = target.scopes().get(model);
        if (mine == null) {
            // Neither holds a rule: both resolve to the model's own default, which is the same rows.
            return theirs == null;
        }
        if (mine.condition() != null && !mine.condition().equals(theirs == null ? null : theirs.condition())) {
            return false;
        }
        if (mine.rules().contains(Rule.ALL)) {
            return true;
        }
        // No rule on the target falls back to the model's default, which is not something the caller's
        // explicit rules can be compared with — refused rather than guessed at.
        return theirs != null && mine.rules().containsAll(theirs.rules());
    }

    /** Company axis: no company rule means every company, so the target must be no wider. */
    private static boolean coversCompanies(Grants c, Grants target) {
        Scope mine = c.scopes().get(ModelConstant.COMPANY_MODEL);
        if (mine == null || mine.rules().contains(Rule.ALL)) {
            return true;
        }
        Scope theirs = target.scopes().get(ModelConstant.COMPANY_MODEL);
        return theirs != null && mine.rules().containsAll(theirs.rules());
    }

    /**
     * Department axis: a caller role without a department grant reaches every department, so anything
     * is within it; otherwise each of the target's granted departments must sit inside one of the
     * caller role's, and an unbounded target is wider than any bounded caller.
     */
    private static boolean coversDepartments(Grants c, Grants target) {
        Set<String> mine = c.departmentPaths();
        if (mine == null) {
            return true;
        }
        Set<String> theirs = target.departmentPaths();
        if (theirs == null) {
            return false;
        }
        return theirs.stream().allMatch(path -> mine.stream().anyMatch(root -> DepartmentGrants.within(path, root)));
    }

    private static Set<String> union(List<Grants> grants, java.util.function.Function<Grants, Set<String>> part) {
        Set<String> out = new HashSet<>();
        grants.forEach(g -> out.addAll(part.apply(g)));
        return out;
    }

    // ─────────────────────── the data ───────────────────────

    /** What the caller lacks: a page, an action, a field set (all by id), or the rows of a model. */
    public record Violation(Kind kind, String subject) {
        public enum Kind { NAVIGATION, PERMISSION, FIELD_SET, RECORDS, DEPARTMENTS }
    }

    /**
     * One role's static grants.
     *
     * @param departmentPaths the stored {@code idPath} of each department the role's department grant
     *                        names, or {@code null} when the role is not bounded by department (no grant,
     *                        or all departments). Empty reaches no department; a named department that no
     *                        longer exists is left out, since it reaches nothing.
     */
    public record Grants(Set<String> navigations, Set<String> permissions, Set<String> sensitiveFieldSets,
                         Map<String, Scope> scopes, @Nullable Set<String> departmentPaths) {

        /** A role not bounded by department. */
        public Grants(Set<String> navigations, Set<String> permissions, Set<String> sensitiveFieldSets,
                      Map<String, Scope> scopes) {
            this(navigations, permissions, sensitiveFieldSets, scopes, null);
        }

        static Grants empty() {
            return new Grants(new LinkedHashSet<>(), new LinkedHashSet<>(), new LinkedHashSet<>(), new LinkedHashMap<>());
        }

        Grants withDepartmentPaths(@Nullable Set<String> paths) {
            return new Grants(navigations, permissions, sensitiveFieldSets, scopes, paths);
        }
    }

    /** A role's rules on one model and the condition narrowing them. A scope with no rule is no scope. */
    public record Scope(Set<Rule> rules, JsonNode condition) {}

    /** A rule as written: its type and its expression. */
    public record Rule(String scopeType, JsonNode scopeExpr) {
        static final Rule ALL = new Rule("ALL", null);
    }

    private List<Long> callerRoleIds(Long userId) {
        if (userId == null) {
            return List.of();
        }
        Set<Long> roleIds = new HashSet<>();
        for (UserRoleRel rel : userRoleRelService.searchList(new Filters().eq(UserRoleRel::getUserId, userId))) {
            if (rel.getRoleId() != null) {
                roleIds.add(rel.getRoleId());
            }
        }
        if (roleIds.isEmpty()) {
            return List.of();
        }
        return roleService.searchList(new Filters().in(Role::getId, roleIds).eq(Role::getActive, true))
                .stream().map(Role::getId).toList();
    }

    private Map<Long, Grants> load(Collection<Long> roleIds) {
        Map<Long, Grants> out = new LinkedHashMap<>();
        if (roleIds.isEmpty()) {
            return out;
        }
        roleIds.forEach(id -> out.put(id, Grants.empty()));
        for (RoleNavigation rn : roleNavigationService.searchList(new Filters().in(RoleNavigation::getRoleId, roleIds))) {
            Grants g = out.get(rn.getRoleId());
            if (g == null || rn.getNavigationId() == null) {
                continue;
            }
            g.navigations().add(rn.getNavigationId());
            List<String> pids = JsonUtils.toStringList(rn.getPermissionIds(), true);
            if (pids != null) {
                g.permissions().addAll(pids);
            }
        }
        for (RoleDataScope rds : roleDataScopeService.searchList(new Filters().in(RoleDataScope::getRoleId, roleIds))) {
            Grants g = out.get(rds.getRoleId());
            Set<Rule> rules = rulesOf(rds.getDataScopes());
            if (g == null || rds.getModel() == null || rules.isEmpty()) {
                continue;
            }
            JsonNode condition = rds.getScopeCondition();
            boolean hasCondition = condition != null && condition.isArray() && !condition.isEmpty();
            g.scopes().put(rds.getModel(), new Scope(rules, hasCondition ? condition : null));
        }
        for (RoleSensitiveFieldSet r : roleSensitiveFieldSetService.searchList(
                new Filters().in(RoleSensitiveFieldSet::getRoleId, roleIds))) {
            Grants g = out.get(r.getRoleId());
            if (g != null && r.getSensitiveFieldSetId() != null) {
                g.sensitiveFieldSets().add(r.getSensitiveFieldSetId());
            }
        }
        loadDepartmentPaths(out);
        return out;
    }

    /**
     * Each role's department grant, as the {@code idPath} of every department it names. Read only where
     * the role model declares the grant; a role without one keeps {@code null}, which is every role of an
     * application that has not configured any.
     */
    private void loadDepartmentPaths(Map<Long, Grants> grants) {
        if (!DepartmentGrants.declared()) {
            return;
        }
        Map<Long, Set<Long>> rootsByRole = new LinkedHashMap<>();
        for (Role role : roleService.searchList(new Filters().in(Role::getId, grants.keySet()))) {
            Set<Long> roots = DepartmentGrants.roots(role.getDepartmentGrant());
            if (roots != null && grants.containsKey(role.getId())) {
                rootsByRole.put(role.getId(), roots);
            }
        }
        if (rootsByRole.isEmpty()) {
            return;
        }
        Set<Long> allRoots = new HashSet<>();
        rootsByRole.values().forEach(allRoots::addAll);
        Map<Long, String> paths = DepartmentGrants.idPathsOf(modelService, allRoots);
        rootsByRole.forEach((roleId, roots) -> {
            Set<String> rolePaths = new LinkedHashSet<>();
            roots.forEach(root -> {
                String path = paths.get(root);
                if (path != null) {
                    rolePaths.add(path);
                }
            });
            grants.put(roleId, grants.get(roleId).withDepartmentPaths(rolePaths));
        });
    }

    /** Same reading the permission snapshot does: entries without a type are skipped. */
    static Set<Rule> rulesOf(JsonNode dataScopes) {
        Set<Rule> rules = new LinkedHashSet<>();
        if (dataScopes == null || !dataScopes.isArray()) {
            return rules;
        }
        for (JsonNode el : dataScopes) {
            JsonNode type = el == null ? null : el.get("scopeType");
            if (type == null || !type.isString()) {
                continue;
            }
            JsonNode expr = el.get("scopeExpr");
            // ALL takes no expression; one left behind by an editor must not make it a different rule.
            boolean noExpr = expr == null || expr.isNull() || Rule.ALL.scopeType().equals(type.asString());
            rules.add(new Rule(type.asString(), noExpr ? null : expr));
        }
        return rules;
    }

    /** Each sensitive field set's model, with the models it is attached to. */
    private Map<String, Set<String>> modelsOfSets(Set<String> setIds) {
        if (setIds.isEmpty()) {
            return Map.of();
        }
        return inSystemContext(() -> {
            Map<String, Set<String>> out = new HashMap<>();
            modelService.searchList(M_SENSITIVE_FIELD_SET,
                            new FlexQuery(List.of("id", "model", "attachedTo"), new Filters().in("id", setIds)))
                    .forEach(row -> {
                        Set<String> models = new LinkedHashSet<>();
                        if (row.get("model") != null) models.add(String.valueOf(row.get("model")));
                        Object attached = row.get("attachedTo");
                        JsonNode node = attached instanceof JsonNode j ? j
                                : attached == null ? null : JsonUtils.stringToObject(attached.toString(), JsonNode.class);
                        if (node != null && node.isArray()) node.forEach(m -> models.add(m.asString()));
                        out.put(String.valueOf(row.get("id")), models);
                    });
            return out;
        });
    }

    /** Each permission's primary model: the model of the navigation it belongs to. */
    private Map<String, String> modelsOf(Set<String> permissionIds) {
        if (permissionIds.isEmpty()) {
            return Map.of();
        }
        return inSystemContext(() -> {
            List<Map<String, Object>> perms = modelService.searchList(M_PERMISSION,
                    new FlexQuery(List.of("id", "navigationId"), new Filters().in("id", permissionIds)));
            Map<String, String> navByPermission = new HashMap<>();
            perms.forEach(p -> {
                if (p.get("navigationId") != null) {
                    navByPermission.put(String.valueOf(p.get("id")), String.valueOf(p.get("navigationId")));
                }
            });
            if (navByPermission.isEmpty()) {
                return Map.of();
            }
            List<Map<String, Object>> navs = modelService.searchList(M_NAVIGATION,
                    new FlexQuery(List.of("id", "model"), new Filters().in("id", new HashSet<>(navByPermission.values()))));
            Map<String, String> modelByNav = new HashMap<>();
            navs.forEach(n -> {
                Object model = n.get("model");
                if (model != null && !model.toString().isBlank()) {
                    modelByNav.put(String.valueOf(n.get("id")), model.toString());
                }
            });
            Map<String, String> out = new HashMap<>();
            navByPermission.forEach((pid, nav) -> {
                String model = modelByNav.get(nav);
                if (model != null) {
                    out.put(pid, model);
                }
            });
            return out;
        });
    }
}
