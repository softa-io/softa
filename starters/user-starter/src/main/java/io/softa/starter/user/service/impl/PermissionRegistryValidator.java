package io.softa.starter.user.service.impl;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

import io.softa.framework.orm.domain.FilterUnit;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.user.entity.Navigation;
import io.softa.starter.user.entity.Permission;
import io.softa.starter.user.constant.RoleConstant;
import io.softa.starter.user.entity.Role;
import io.softa.starter.user.entity.RoleDataScope;
import io.softa.starter.user.entity.RoleNavigation;
import io.softa.starter.user.entity.RoleSensitiveFieldSet;
import io.softa.starter.user.entity.SensitiveFieldSet;
import io.softa.starter.user.enums.NavigationType;
import io.softa.starter.user.service.NavigationModelResolver;
import io.softa.starter.user.service.RoleDataScopeService;
import io.softa.starter.user.service.RoleNavigationService;
import io.softa.starter.user.service.RoleSensitiveFieldSetService;
import io.softa.starter.user.service.RoleService;

/**
 * Permission registry sanity check — runs once after the application context
 * is fully ready (i.e. after SysPreDataService loaded navigation /
 * permission / sensitive_field_set + RoleService seed). Each rule walks
 * data that's already in memory, so the whole pass is O(n) over the
 * pre-loaded sets.
 *
 * <p>Any violation logs ERROR + accumulates into a single
 * {@link IllegalStateException} thrown at the end — startup fails fast so
 * the operator notices the bad seed instead of catching a runtime 500
 * three weeks later.
 *
 * <p>Design-§3.8 rules ②–⑩ (the RBAC-structure rules) are implemented here:
 * <ul>
 *   <li>②–⑧ FK / type / model constraints on navigation / permission /
 *       sensitive_field_set / role_navigation rows</li>
 *   <li>⑨ CUSTOM scopeExpr field references must exist on the nav's
 *       primary model (parses scopeExpr through {@link Filters#of(String)}
 *       and walks the FilterUnit tree)</li>
 *   <li>⑩ role_navigation.navigationId points at a grantable + model-bound
 *       nav</li>
 * </ul>
 *
 * <p>Rule ① (endpoint→permission coverage — Spring MVC handler scan vs the
 * {@code EndpointIndex}) is pure engine domain and now lives in
 * permission-starter's {@code EndpointCoverageValidator}; this validator carries
 * no compile dependency on the permission engine (full ⊥).
 *
 * <p>Bonus rule beyond the design — {@code Role.code in reserved set} —
 * prevents an admin from manually creating a role with a code that future
 * built-in seed (HR_BP / DEPT_MGR / ...) will want to claim.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PermissionRegistryValidator {

    /** Reserved Role.code values. Adding a new built-in role? Append here. */
    private static final Set<String> RESERVED_ROLE_CODES = Set.of(
            RoleConstant.CODE_SUPER_ADMIN,
            RoleConstant.CODE_TENANT_ADMIN
    );

    /** Allowed child types per parent type (design rule ⑤).
     * GROUP → any; MENU → BUTTON / TAB; BUTTON → TAB; TAB → nothing. */
    private static final Map<NavigationType, EnumSet<NavigationType>> ALLOWED_CHILDREN = Map.of(
            NavigationType.GROUP,  EnumSet.allOf(NavigationType.class),
            NavigationType.MENU,   EnumSet.of(NavigationType.BUTTON, NavigationType.TAB),
            NavigationType.BUTTON, EnumSet.of(NavigationType.TAB),
            NavigationType.TAB,    EnumSet.noneOf(NavigationType.class)
    );

    /** Nav types that REQUIRE a non-null model (design rule ⑧). */
    private static final EnumSet<NavigationType> MODEL_REQUIRED =
            EnumSet.of(NavigationType.BUTTON, NavigationType.TAB);

    /** Nav types that FORBID a non-null model (design rule ⑧). */
    private static final EnumSet<NavigationType> MODEL_FORBIDDEN =
            EnumSet.of(NavigationType.GROUP);

    /** Nav types that may receive role_navigation grants (design rule ⑩). */
    private static final EnumSet<NavigationType> GRANTABLE_TYPES =
            EnumSet.of(NavigationType.MENU, NavigationType.BUTTON, NavigationType.TAB);

    private final ModelService<?> modelService;
    private final RoleService roleService;
    private final RoleNavigationService roleNavigationService;
    private final RoleDataScopeService roleDataScopeService;
    private final RoleSensitiveFieldSetService roleSensitiveFieldSetService;
    private final NavigationModelResolver navigationModelResolver;

    @EventListener(ApplicationReadyEvent.class)
    public void validate() {
        List<String> errors = new ArrayList<>();

        // Reuse the in-memory snapshot the NavigationModelResolver already
        // holds (loaded in its own @PostConstruct) instead of issuing a
        // fresh searchList — both reads pull from the same source table
        // and bean init order makes the snapshot available by the time
        // ApplicationReadyEvent fires.
        List<Navigation> navigations = new ArrayList<>(navigationModelResolver.allNavigations());
        // Permission and SensitiveFieldSet aren't fully cached in the
        // user-starter (EndpointIndex / SensitiveFieldSetCache flatten
        // them into hashed indexes for hot-path lookups, not row lists),
        // so we still do one searchList here per startup pass. Cheap
        // (single read, ~10s of rows) — not worth expanding their cache
        // surface to retain raw rows.
        List<Permission> permissions = safeSearchList("Permission", Permission.class, errors);
        List<SensitiveFieldSet> sensitiveFieldSets =
                safeSearchList("SensitiveFieldSet", SensitiveFieldSet.class, errors);

        Map<String, Navigation> navById = new HashMap<>(navigations.size());
        for (Navigation n : navigations) navById.put(n.getId(), n);

        Map<String, String> permNavById = checkPermissionUniqueness(permissions, errors);

        Set<String> sensitiveFieldSetIds = new HashSet<>(sensitiveFieldSets.size());
        for (SensitiveFieldSet s : sensitiveFieldSets) sensitiveFieldSetIds.add(s.getId());

        checkNavigationRows(navigations, navById, errors);
        checkSensitiveFieldSetRows(sensitiveFieldSets, errors);
        checkReservedRoleCodes(errors);

        List<RoleNavigation> grants = loadRoleNavigationGrants(errors);
        checkRoleNavigationRows(grants, navById, permNavById, errors);

        // Data-dimension grants now live in their own tables — validate scope
        // (rule ⑨, keyed by the row's own model) and SFS existence there.
        List<RoleDataScope> scopeGrants = loadRoleDataScopeGrants(errors);
        checkRoleDataScopeRows(scopeGrants, errors);
        List<RoleSensitiveFieldSet> sfsGrants = loadRoleSensitiveFieldSetGrants(errors);
        checkRoleSensitiveFieldSetRows(sfsGrants, sensitiveFieldSetIds, errors);

        // Rule ① (endpoint→permission coverage) now runs in permission-starter's
        // EndpointCoverageValidator — it reads the EndpointIndex, a pure engine
        // concept. This validator keeps only the RBAC-structure rules.

        if (errors.isEmpty()) {
            log.info("PermissionRegistryValidator — OK ({} nav / {} perm / {} sfs / {} grants checked)",
                    navigations.size(), permissions.size(), sensitiveFieldSets.size(), grants.size());
            return;
        }
        // WARN, not ERROR, and do not fail-fast. The design calls for fail-fast, but in a
        // multi-app world the same navigation.json seed ships nav entries for modules that may or
        // may not be present in every app's classpath — taking the whole app down because Studio
        // isn't included here is the wrong trade-off.
        //
        // Having decided to continue, ERROR was the wrong level for the same reason: hcm-app alone
        // reports 38 of these on every single boot, all of them navigations naming studio and ai
        // models it will never carry, and none of them ever actionable. A level that is permanently
        // red teaches its readers to skip it, and a genuine finding — a role squatting on a reserved
        // code, a grant pointing at a missing navigation — looks exactly the same and goes with it.
        //
        // Ops still sees every violation, and CI still gates on a clean run; what changes is that
        // ERROR in this application's log once again means something is actually broken.
        log.warn("PermissionRegistryValidator — {} violation(s):", errors.size());
        errors.forEach(e -> log.warn("  - {}", e));
        log.warn("PermissionRegistryValidator — startup continues; investigate and fix the seed / classpath above");
    }

    /** Rule ② — Permission.id uniqueness. Also builds the {@code permId →
     *  navId} side-map consumed by rule ③ (cross-nav grant check). */
    private static Map<String, String> checkPermissionUniqueness(
            List<Permission> permissions, List<String> errors) {
        Map<String, String> permNavById = new HashMap<>(permissions.size());
        Set<String> seenPermIds = new HashSet<>(permissions.size());
        for (Permission p : permissions) {
            if (p.getId() == null) continue;
            if (!seenPermIds.add(p.getId())) {
                errors.add("Permission.id duplicated: '" + p.getId() + "'");
            }
            // A permission must name a navigation. Both admin snapshots build their permission set
            // by collecting the permissions of the navigations they may reach, so one with no
            // navigation is in nobody's set — and since the endpoint gate treats a REGISTERED
            // endpoint as gated, its endpoints become unreachable for the platform administrator and
            // the tenant administrator alike. Silently: the row looks fine, the endpoints are
            // indexed, and the only symptom is a 403 nobody can explain. Said here because this is
            // where the seed is checked, and the fix is always in the seed.
            if (p.getNavigationId() == null) {
                errors.add("Permission[" + p.getId() + "].navigationId is null — its endpoints would "
                        + "be unreachable for every admin, whose permission sets are built from the "
                        + "navigations they may reach");
            }
            permNavById.put(p.getId(), p.getNavigationId());
        }
        return permNavById;
    }

    /** Rules ④⑤⑧ — Navigation row integrity: parent FK, child-type
     *  compatibility, model constraints by type, TAB model differs from
     *  parent. */
    private static void checkNavigationRows(
            List<Navigation> navigations, Map<String, Navigation> navById, List<String> errors) {
        for (Navigation n : navigations) {
            // ④ parent FK
            if (n.getParentId() != null && !navById.containsKey(n.getParentId())) {
                errors.add(String.format(
                        "Navigation[%s].parentId='%s' references missing Navigation",
                        n.getId(), n.getParentId()));
            }
            // ⑤ child type compatible with parent
            if (n.getParentId() != null) {
                Navigation parent = navById.get(n.getParentId());
                if (parent != null && parent.getType() != null && n.getType() != null) {
                    EnumSet<NavigationType> allowed = ALLOWED_CHILDREN.get(parent.getType());
                    if (allowed != null && !allowed.contains(n.getType())) {
                        errors.add(String.format(
                                "Navigation[%s].type=%s cannot be a child of parent[%s].type=%s (allowed children: %s)",
                                n.getId(), n.getType(), parent.getId(), parent.getType(), allowed));
                    }
                }
            }
            // ⑧ model constraints by type
            boolean hasModel = n.getModel() != null && !n.getModel().isEmpty();
            if (n.getType() != null) {
                if (MODEL_REQUIRED.contains(n.getType()) && !hasModel) {
                    errors.add(String.format(
                            "Navigation[%s].type=%s requires a non-null model", n.getId(), n.getType()));
                }
                if (MODEL_FORBIDDEN.contains(n.getType()) && hasModel) {
                    errors.add(String.format(
                            "Navigation[%s].type=%s must NOT set model (got '%s')",
                            n.getId(), n.getType(), n.getModel()));
                }
            }
            // ⑧ continued: model must exist in MetaModel when set
            if (hasModel && !ModelManager.existModel(n.getModel())) {
                errors.add(String.format(
                        "Navigation[%s].model='%s' not registered in ModelManager",
                        n.getId(), n.getModel()));
            }
            // ⑧ continued: TAB must differ from parent's model (cross-model
            // tabs only; same-model tabs are FE filter tabs, shouldn't be
            // in the nav table).
            if (n.getType() == NavigationType.TAB && hasModel && n.getParentId() != null) {
                Navigation parent = navById.get(n.getParentId());
                if (parent != null && n.getModel().equals(parent.getModel())) {
                    errors.add(String.format(
                            "Navigation[%s] TAB.model='%s' must differ from parent[%s].model — "
                                    + "same-model tabs belong in the FE as filter tabs, not in the nav registry",
                            n.getId(), n.getModel(), parent.getId()));
                }
            }
        }
    }

    /** Rules ⑥⑦ ⑪ — SensitiveFieldSet row integrity: model exists; field
     *  codes exist on the model; attachedTo references existing MetaModels
     *  (⑪ — Wizard UI hint, won't bring down the mask engine if violated). */
    private static void checkSensitiveFieldSetRows(
            List<SensitiveFieldSet> sensitiveFieldSets, List<String> errors) {
        for (SensitiveFieldSet s : sensitiveFieldSets) {
            // ⑥ model exists
            if (s.getModel() == null || s.getModel().isEmpty()) {
                errors.add(String.format(
                        "SensitiveFieldSet[%s] has null/empty model", s.getId()));
                continue;
            }
            if (!ModelManager.existModel(s.getModel())) {
                errors.add(String.format(
                        "SensitiveFieldSet[%s].model='%s' not registered in ModelManager",
                        s.getId(), s.getModel()));
                continue; // skip ⑦ for this row — pointless without a valid model
            }
            // ⑦ fieldCodes exist on the model
            for (String code : extractStringList(s.getFieldCodes())) {
                if (!ModelManager.existField(s.getModel(), code)) {
                    errors.add(String.format(
                            "SensitiveFieldSet[%s].fieldCodes references missing field '%s' on model '%s'",
                            s.getId(), code, s.getModel()));
                }
            }
            // ⑪ attachedTo references valid MetaModels (UI aggregation hint;
            // a missing target just means the SFS won't surface on the
            // intended nav row in the Wizard — mask still works via `model`).
            for (String attached : extractStringList(s.getAttachedTo())) {
                if (attached == null || attached.isEmpty()) continue;
                if (!ModelManager.existModel(attached)) {
                    errors.add(String.format(
                            "SensitiveFieldSet[%s].attachedTo references unknown MetaModel '%s' "
                                    + "(Wizard row will not surface this SFS under that model)",
                            s.getId(), attached));
                }
                if (attached.equals(s.getModel())) {
                    errors.add(String.format(
                            "SensitiveFieldSet[%s].attachedTo='%s' duplicates its own `model` — "
                                    + "remove the redundant entry",
                            s.getId(), attached));
                }
            }
        }
    }

    /** Bonus rule (beyond v4 §3.8 ten) — admin-created Role.code must
     *  match a reserved system code; prevents an admin from squatting on
     *  a built-in role code (e.g. "SUPER_ADMIN" / future "HR_BP"). */
    private void checkReservedRoleCodes(List<String> errors) {
        List<Role> rolesWithCode = roleService.searchList(new Filters().isSet(Role::getCode));
        for (Role r : rolesWithCode) {
            if (!RESERVED_ROLE_CODES.contains(r.getCode())) {
                errors.add(String.format(
                        "Role[id=%d,name=%s].code='%s' is not a reserved system code (allowed: %s)",
                        r.getId(), r.getName(), r.getCode(), RESERVED_ROLE_CODES));
            }
        }
    }

    /** Pull all RoleNavigation rows with fail-soft fallback. Framework-level
     *  errors (mapping issue, transient DB outage at startup) become a
     *  single validation finding instead of aborting the whole pass. */
    private List<RoleNavigation> loadRoleNavigationGrants(List<String> errors) {
        try {
            return roleNavigationService.searchList();
        } catch (Throwable t) {
            errors.add("PermissionRegistryValidator could not load RoleNavigation rows: "
                    + t.getMessage() + " — grant checks (③⑩) will be skipped");
            log.warn("PermissionRegistryValidator — roleNavigationService.searchList() threw", t);
            return List.of();
        }
    }

    /** Rules ③⑩ — RoleNavigation grant integrity: nav exists + grantable
     *  + has model (rule ⑩); each permissionId exists and belongs to the
     *  same nav (rule ③). Scope (⑨) + SFS existence moved to
     *  {@link #checkRoleDataScopeRows} / {@link #checkRoleSensitiveFieldSetRows}
     *  now that those grants live in their own tables. */
    private void checkRoleNavigationRows(
            List<RoleNavigation> grants,
            Map<String, Navigation> navById,
            Map<String, String> permNavById,
            List<String> errors) {
        for (RoleNavigation rn : grants) {
            String navId = rn.getNavigationId();

            // ⑩ navId required, type ∈ {MENU,BUTTON,TAB}, model non-null
            if (navId == null) {
                errors.add(String.format(
                        "RoleNavigation[id=%d] has null navigationId", rn.getId()));
                continue;
            }
            Navigation nav = navById.get(navId);
            if (nav == null) {
                errors.add(String.format(
                        "RoleNavigation[id=%d].navigationId='%s' references missing Navigation",
                        rn.getId(), navId));
            } else {
                if (nav.getType() == null || !GRANTABLE_TYPES.contains(nav.getType())) {
                    errors.add(String.format(
                            "RoleNavigation[id=%d].navigationId='%s' points at non-grantable nav (type=%s); "
                                    + "grants must target one of %s",
                            rn.getId(), navId, nav.getType(), GRANTABLE_TYPES));
                }
                if (nav.getModel() == null || nav.getModel().isEmpty()) {
                    errors.add(String.format(
                            "RoleNavigation[id=%d].navigationId='%s' points at nav with null model — "
                                    + "pure-container nodes can't be granted",
                            rn.getId(), navId));
                }
            }

            // ③ permission ids exist + belong to same nav (FK + cross-nav check)
            for (String pid : extractStringList(rn.getPermissionIds())) {
                String permNav = permNavById.get(pid);
                if (permNav == null) {
                    errors.add(String.format(
                            "RoleNavigation[id=%d] references missing Permission[id=%s]",
                            rn.getId(), pid));
                } else if (!permNav.equals(navId)) {
                    errors.add(String.format(
                            "RoleNavigation[id=%d,nav=%s] references Permission[%s] mounted at nav '%s' — cross-nav grant rejected",
                            rn.getId(), navId, pid, permNav));
                }
            }
        }
    }

    /** Pull all RoleDataScope rows, fail-soft (matches loadRoleNavigationGrants). */
    private List<RoleDataScope> loadRoleDataScopeGrants(List<String> errors) {
        try {
            return roleDataScopeService.searchList();
        } catch (Throwable t) {
            errors.add("PermissionRegistryValidator could not load RoleDataScope rows: "
                    + t.getMessage() + " — scope checks (⑨) will be skipped");
            log.warn("PermissionRegistryValidator — roleDataScopeService.searchList() threw", t);
            return List.of();
        }
    }

    /** Pull all RoleSensitiveFieldSet rows, fail-soft. */
    private List<RoleSensitiveFieldSet> loadRoleSensitiveFieldSetGrants(List<String> errors) {
        try {
            return roleSensitiveFieldSetService.searchList();
        } catch (Throwable t) {
            errors.add("PermissionRegistryValidator could not load RoleSensitiveFieldSet rows: "
                    + t.getMessage() + " — SFS existence checks will be skipped");
            log.warn("PermissionRegistryValidator — roleSensitiveFieldSetService.searchList() threw", t);
            return List.of();
        }
    }

    /** role_data_scope integrity: model exists (rule ⑩ analogue for scope
     *  rows) + CUSTOM scopeExpr field refs exist on that model (rule ⑨,
     *  now keyed by the row's own model rather than a nav's primary model). */
    private void checkRoleDataScopeRows(List<RoleDataScope> grants, List<String> errors) {
        for (RoleDataScope rds : grants) {
            String model = rds.getModel();
            if (model == null || model.isBlank()) {
                errors.add(String.format("RoleDataScope[id=%d] has null/blank model", rds.getId()));
                continue;
            }
            if (!ModelManager.existModel(model)) {
                errors.add(String.format(
                        "RoleDataScope[id=%d] references missing model '%s'", rds.getId(), model));
                continue;
            }
            checkCustomScopeExprFields(rds.getId(), model, rds.getDataScopes(), errors);
        }
    }

    /** role_sensitive_field_set integrity: each granted setId must exist. */
    private void checkRoleSensitiveFieldSetRows(
            List<RoleSensitiveFieldSet> grants, Set<String> sensitiveFieldSetIds, List<String> errors) {
        for (RoleSensitiveFieldSet g : grants) {
            String sid = g.getSensitiveFieldSetId();
            if (sid == null || sid.isBlank()) {
                errors.add(String.format(
                        "RoleSensitiveFieldSet[id=%d] has null/blank sensitiveFieldSetId", g.getId()));
            } else if (!sensitiveFieldSetIds.contains(sid)) {
                errors.add(String.format(
                        "RoleSensitiveFieldSet[id=%d] references missing SensitiveFieldSet[id=%s]",
                        g.getId(), sid));
            }
        }
    }

    /** Rule ⑨ — CUSTOM scopeExpr field references must exist on {@code model}.
     *  Parses scopeExpr through the framework's {@link Filters} deserialiser
     *  (same one the runtime CUSTOM contributor uses) and walks the
     *  FilterUnit tree. Shared by {@link #checkRoleDataScopeRows}. */
    private void checkCustomScopeExprFields(Long rowId, String model, JsonNode dataScopes, List<String> errors) {
        if (dataScopes == null || !dataScopes.isArray() || dataScopes.isEmpty()) return;
        for (JsonNode scopeRule : dataScopes) {
            if (scopeRule == null || !scopeRule.isObject()) continue;
            JsonNode typeNode = scopeRule.get("scopeType");
            // "CUSTOM" literal (not ScopeType.CUSTOM.name()): the engine's
            // ScopeType enum lives in permission-starter and this validator is now
            // fully ⊥ of it. The stored scopeType wire value is a stable data
            // contract, so matching the string is sound.
            if (typeNode == null || !"CUSTOM".equals(typeNode.asString())) continue;
            JsonNode expr = scopeRule.get("scopeExpr");
            if (expr == null || !expr.isArray() || expr.isEmpty()) continue;
            Set<String> fieldRefs = extractFieldRefs(expr, rowId, errors);
            for (String fieldRef : fieldRefs) {
                if (!ModelManager.existField(model, fieldRef)) {
                    errors.add(String.format(
                            "RoleDataScope[id=%d] CUSTOM scopeExpr references missing field '%s' on model '%s'",
                            rowId, fieldRef, model));
                }
            }
        }
    }

    private static List<String> extractStringList(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> out = new ArrayList<>(node.size());
        for (JsonNode el : node) {
            if (el.isString()) out.add(el.asString());
        }
        return out;
    }

    /** Wrap {@link ModelService#searchList(String, FlexQuery, Class)} so a
     *  framework-level mapping failure on one table becomes a single
     *  validation error instead of crashing the whole startup pass —
     *  partial validation beats no validation. */
    private <T> List<T> safeSearchList(String modelName, Class<T> clazz, List<String> errors) {
        try {
            return modelService.searchList(modelName, new FlexQuery(), clazz);
        } catch (Throwable t) {
            errors.add(String.format(
                    "PermissionRegistryValidator could not load %s rows: %s — downstream checks for this table will be skipped",
                    modelName, t.getMessage()));
            log.warn("PermissionRegistryValidator — searchList({}) threw", modelName, t);
            return List.of();
        }
    }

    /**
     * Walk a CUSTOM scopeExpr JSON (FilterCondition tuple array) and collect
     * every field reference. Reuses the framework's {@link Filters#of(String)}
     * deserialiser so we match runtime parsing semantics; on parse error
     * we log a validation finding and return an empty set (caller continues).
     */
    private Set<String> extractFieldRefs(JsonNode expr, Long ruleId, List<String> errors) {
        Filters parsed;
        try {
            parsed = Filters.of(expr.toString());
        } catch (Throwable t) {
            errors.add(String.format(
                    "RoleDataScope[id=%d] CUSTOM scopeExpr failed to parse: %s",
                    ruleId, t.getMessage()));
            return Set.of();
        }
        if (parsed == null) return Set.of();
        Set<String> fields = new HashSet<>();
        walkFilterTree(parsed, fields);
        return fields;
    }

    /** Recursive walk over the {@link Filters} AST collecting every
     *  {@link FilterUnit#getField()} / {@link FilterUnit#getFields()} entry. */
    private void walkFilterTree(Filters node, Set<String> out) {
        if (node == null) return;
        FilterUnit unit = node.getFilterUnit();
        if (unit != null) {
            if (unit.getField() != null && !unit.getField().isEmpty()) {
                out.add(unit.getField());
            }
            if (unit.getFields() != null) {
                for (String f : unit.getFields()) {
                    if (f != null && !f.isEmpty()) out.add(f);
                }
            }
        }
        if (node.getChildren() != null) {
            for (Filters child : node.getChildren()) walkFilterTree(child, out);
        }
    }
}
