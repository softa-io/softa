package io.softa.starter.user.controller;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.user.constant.RoleConstant;
import io.softa.starter.user.dto.EffectiveAccess;
import io.softa.starter.user.dto.RoleActiveDTO;
import io.softa.starter.user.dto.WizardSaveDTO;
import io.softa.starter.user.entity.Role;
import io.softa.starter.user.entity.RoleDataScope;
import io.softa.starter.user.entity.RoleNavigation;
import io.softa.starter.user.entity.RoleSensitiveFieldSet;
import io.softa.starter.user.entity.UserRoleRel;
import io.softa.starter.user.enums.UserRoleSource;
import io.softa.starter.user.service.DynamicRoleSyncJob;
import io.softa.starter.user.service.RoleDataScopeService;
import io.softa.starter.user.service.RoleNavigationService;
import io.softa.starter.user.service.RoleSensitiveFieldSetService;
import io.softa.starter.user.service.RoleService;
import io.softa.starter.user.service.UserRoleRelService;
import io.softa.starter.user.service.impl.UiContextBuilder;
import io.softa.starter.user.util.JsonArrayUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Role controller — mapped under {@code /Role} to share the generic model
 * path namespace. Hosts the wizard create/update ({@code /Role/wizard},
 * {@code /Role/{id}/wizard}), the status toggle ({@code /Role/{id}/active}),
 * and the typed DELETE endpoints ({@code /Role/deleteById(s)}) that route
 * through {@link RoleService} so the system-role guard + cache eviction run —
 * the generic {@code /{modelName}} delete path skips both. Reads and generic
 * writes not declared here fall through to the generic {@code ModelController};
 * the literal {@code /Role/*} mappings here are more specific and win.
 */
@Tag(name = "Role")
@RestController
@RequestMapping("/Role")
@RequiredArgsConstructor
public class RoleController extends SystemRoleGuardedController<RoleService, Role, Long> {

    /**
     * {@code ScopeType.ALL}'s wire form. The name, not the {@code @JsonValue} code: the snapshot reads
     * these rows back with {@code ScopeType.valueOf}, so the name is what is stored. permission-starter
     * is not on this module's classpath, so this cannot be the enum constant.
     */
    static final String SCOPE_TYPE_ALL = "ALL";

    private final RoleService roleService;
    private final RoleNavigationService roleNavigationService;
    private final RoleDataScopeService roleDataScopeService;
    private final RoleSensitiveFieldSetService roleSensitiveFieldSetService;
    private final UserRoleRelService userRoleRelService;
    private final DynamicRoleSyncJob dynamicRoleSyncJob;
    private final UiContextBuilder uiContextBuilder;

    @Override
    protected String modelName() {
        return "Role";
    }

    /**
     * Typed service, not {@code ModelService}: {@code RoleServiceImpl} evicts the per-role permission
     * cache and applies its own field-level {@code guardSystemMutation}. The mapped endpoints and their
     * {@code SystemRoleWriteGuard} calls live in the base class and are final — the two former private
     * guards here ({@code rejectClientCode} on create, "not a system role" on update) are exactly what
     * the shared guard does for every write verb, not just the three this class used to declare.
     */
    @Override
    protected boolean doDeleteById(Long id) {
        return roleService.deleteById(id);
    }

    @Override
    protected boolean doDeleteByIds(List<Long> ids) {
        return roleService.deleteByIds(ids);
    }

    @GetMapping("/{id}/effective-access")
    @Operation(summary = "Read-only computed navigation/permissions for an admin role — SUPER_ADMIN = every "
            + "nav+permission; TENANT_ADMIN = tenant-facing navs (minus platform-only) narrowed by the tenant's "
            + "plan; row-scope + sensitive fields unrestricted (admin bypass). Non-admin roles return "
            + "{computed:false} — the FE renders their static role_navigation grants instead.")
    public ApiResponse<EffectiveAccess> effectiveAccess(@PathVariable Long id) {
        Role role = roleService.getById(id)
                .orElseThrow(() -> new BusinessException("Role not found."));
        // Admin role → computed navs/permissions; non-admin → {computed:false} (FE uses static grants).
        return ApiResponse.success(uiContextBuilder.effectiveAccessForRole(role.getCode(), role.getTenantId()));
    }

    @PostMapping("/wizard")
    @Transactional
    @Operation(summary = "Wizard create — insert Role + role_navigation + user_role rows (Manual + Dynamic) in one transaction; returns new role id")
    public ApiResponse<Long> createWithWizard(@RequestBody @Valid WizardSaveDTO body) {
        Role role = parseRole(body.roleUpdate());
        role.setId(null);
        Long newId = roleService.createOne(role);
        writeRoleNavigations(newId, body.roleNavigations());
        writeRoleDataScopes(newId, body.roleDataScopes());
        writeRoleSensitiveFieldSets(newId, body.roleSensitiveFieldSetIds());
        writeManualUserRoleRels(newId, body.userIds());
        // Inline DYNAMIC sync — same in-transaction guarantee. The cron
        // job covers the in-between drift (employee data changes between
        // role saves) once it's running.
        dynamicRoleSyncJob.syncRole(newId);
        return ApiResponse.success(newId);
    }

    @PutMapping("/{id}/wizard")
    @Transactional
    @Operation(summary = "Wizard update — refresh Role basics, rewrite role_navigation rows and user_role rows (Manual + Dynamic) in one transaction")
    public ApiResponse<Void> saveWizard(@PathVariable Long id, @RequestBody @Valid WizardSaveDTO body) {
        Role role = parseRole(body.roleUpdate());
        role.setId(id);
        roleService.updateOne(role, true);
        // Wizard "Clear" on the dynamic membership rule sends
        // {"dynamicFilter": null} explicitly. The entity-based updateOne
        // above runs with ignoreNull=true (to avoid clobbering fields the
        // wizard payload didn't touch, e.g. code / tenantId), which means
        // it skips null values — including our intended clear. Issue a
        // targeted column-only update through the framework's map-based
        // ModelService.updateOne, which writes whatever keys are in the
        // map regardless of their value, so the SQL becomes a literal
        // `SET dynamic_filter = NULL`.
        if (isExplicitNull(body.roleUpdate(), "dynamicFilter")) {
            Map<String, Object> clearFields = new HashMap<>();
            clearFields.put("id", id);
            clearFields.put("dynamicFilter", null);
            modelService.updateOne("Role", clearFields);
        }
        roleNavigationService.deleteByFilters(new Filters().eq(RoleNavigation::getRoleId, id));
        writeRoleNavigations(id, body.roleNavigations());
        // Rewrite the data-dimension grants (scope + SFS). Same delete-then-
        // insert pattern, each scoped to THIS role's rows only. Whole save is
        // one @Transactional so observers never see the empty intermediate state.
        roleDataScopeService.deleteByFilters(new Filters().eq(RoleDataScope::getRoleId, id));
        writeRoleDataScopes(id, body.roleDataScopes());
        roleSensitiveFieldSetService.deleteByFilters(new Filters().eq(RoleSensitiveFieldSet::getRoleId, id));
        writeRoleSensitiveFieldSets(id, body.roleSensitiveFieldSetIds());
        // Wipe ALL existing user_role rows for this role — both Manual and
        // Dynamic — then rebuild from scratch. By application convention
        // there's at most one row per (user, role): Manual takes precedence,
        // syncRole inserts Dynamic only for users not in the Manual list.
        // Doing a full wipe (instead of delta upsert) keeps the logic
        // trivially correct: no "what about old rows that should be
        // upgraded/downgraded" branches. The whole save is in one
        // @Transactional so external observers never see the empty state.
        userRoleRelService.deleteByFilters(
                new Filters().eq(UserRoleRel::getRoleId, id));
        writeManualUserRoleRels(id, body.userIds());
        dynamicRoleSyncJob.syncRole(id);
        return ApiResponse.success();
    }

    @PutMapping("/{id}/active")
    @Transactional
    @Operation(summary = "Enable / disable a role. Routes through the typed RoleService so it publishes "
            + "RoleNavigationChangedEvent — every holder's PermissionInfo cache is evicted on commit and "
            + "the status change takes effect immediately. The generic /Role/updateOne path publishes no "
            + "event (holders would stay authorised until the 1h cache TTL), so status MUST be changed here.")
    public ApiResponse<Boolean> setActive(@PathVariable Long id, @RequestBody @Valid RoleActiveDTO body) {
        Role patch = new Role();
        patch.setId(id);
        patch.setActive(body.active());
        // ignoreNull=true → writes only `active`, leaving name / code / tenantId
        // untouched. The typed updateOne runs guardSystemMutation (blocks
        // deactivating a system role) and publishRoleGrantChange, whose
        // AFTER_COMMIT listener evicts every holder's PermissionInfo snapshot.
        return ApiResponse.success(roleService.updateOne(patch, true));
    }

    /** True when {@code payload} contains {@code field} as a JSON null
     *  (vs. the field being absent). Used to tell apart "wizard didn't
     *  touch this field" from "wizard explicitly cleared this field" —
     *  the latter needs a column-only update to actually write NULL,
     *  because the entity-based updateOne runs with ignoreNull=true. */
    private static boolean isExplicitNull(JsonNode payload, String field) {
        if (payload == null || !payload.has(field)) return false;
        JsonNode value = payload.get(field);
        return value == null || value.isNull();
    }

    /** Maps the wizard's `roleUpdate` JSON (name / description / active /
     *  dynamicFilter) into a Role entity. We extract each field by hand
     *  instead of going through JsonUtils.jsonNodeToObject(Class<T>) because
     *  the runtime Jackson lacks the `treeToValue(JsonNode, Class)` overload
     *  the helper relies on (NoSuchMethodError on call).
     *
     *  <p>Explicit-null fields (e.g. wizard "Clear" on dynamicFilter) stay
     *  out of the entity here — see {@link #saveWizard} for the targeted
     *  follow-up update that actually writes NULL. */
    private Role parseRole(JsonNode payload) {
        Role role = new Role();
        if (payload == null || payload.isNull()) return role;
        JsonNode name = payload.get("name");
        if (name != null && !name.isNull()) role.setName(name.asString());
        JsonNode description = payload.get("description");
        if (description != null && !description.isNull()) role.setDescription(description.asString());
        JsonNode active = payload.get("active");
        if (active != null && !active.isNull()) role.setActive(active.asBoolean());
        JsonNode dynamicFilter = payload.get("dynamicFilter");
        if (dynamicFilter != null && !dynamicFilter.isNull()) role.setDynamicFilter(dynamicFilter);
        return role;
    }

    /** Inserts one user_role row per id in the wizard payload with source=MANUAL.
     *  Skips when payload is null / not an array / empty. Duplicate ids in the
     *  payload are de-duped (a single (user, role, MANUAL) row is enough).
     *  UserAccount ids arrive as JSON strings (frontend serializes all ids as
     *  string per type convention) but may be numbers in tests — {@link
     *  JsonArrayUtils#toLongList} accepts both. */
    private void writeManualUserRoleRels(Long roleId, JsonNode idsJson) {
        java.util.Set<Long> userIds = new java.util.LinkedHashSet<>(JsonArrayUtils.toLongList(idsJson));
        if (userIds.isEmpty()) return;
        List<UserRoleRel> rows = new ArrayList<>(userIds.size());
        for (Long userId : userIds) {
            UserRoleRel ur = new UserRoleRel();
            ur.setUserId(userId);
            ur.setRoleId(roleId);
            ur.setSource(UserRoleSource.MANUAL);
            rows.add(ur);
        }
        userRoleRelService.createList(rows);
    }

    /** Writes one role_navigation row per entry in the wizard payload — now
     *  menu + permission only ({navigationId, permissionIds}); data scope and
     *  sensitive-field-set moved to their own tables (see
     *  {@link #writeRoleDataScopes} / {@link #writeRoleSensitiveFieldSets}).
     *  Incoming `id` / `roleId` from the frontend are ignored — id is
     *  auto-assigned, roleId is bound by the caller. */
    private void writeRoleNavigations(Long roleId, JsonNode rowsJson) {
        if (rowsJson == null || !rowsJson.isArray() || rowsJson.isEmpty()) return;
        List<RoleNavigation> rows = new ArrayList<>(rowsJson.size());
        for (JsonNode row : rowsJson) {
            RoleNavigation rn = new RoleNavigation();
            rn.setRoleId(roleId);
            JsonNode navIdNode = row.get("navigationId");
            rn.setNavigationId(navIdNode == null || navIdNode.isNull() ? null : navIdNode.asString());
            rn.setPermissionIds(row.get("permissionIds"));
            rows.add(rn);
        }
        roleNavigationService.createList(rows);
    }

    /** Writes one role_data_scope row per {@code {model, dataScopes, scopeCondition?}} entry
     *  (one per queryable model). Entries without a model are skipped.
     *  Incoming id/roleId ignored — id auto-assigned, roleId bound by caller. */
    private void writeRoleDataScopes(Long roleId, JsonNode rowsJson) {
        if (rowsJson == null || !rowsJson.isArray() || rowsJson.isEmpty()) return;
        rejectUnboundedCompanyScope(rowsJson);
        List<RoleDataScope> rows = new ArrayList<>(rowsJson.size());
        for (JsonNode row : rowsJson) {
            JsonNode modelNode = row.get("model");
            if (modelNode == null || modelNode.isNull() || modelNode.asString().isBlank()) continue;
            RoleDataScope rds = new RoleDataScope();
            rds.setRoleId(roleId);
            rds.setModel(modelNode.asString());
            rds.setDataScopes(row.get("dataScopes"));
            JsonNode condition = row.get("scopeCondition");
            if (condition != null && condition.isArray() && !condition.isEmpty()) {
                rds.setScopeCondition(condition);
            }
            rows.add(rds);
        }
        if (!rows.isEmpty()) roleDataScopeService.createList(rows);
    }

    /**
     * Reject a role that may read <b>every</b> row of a model belonging to one company without saying
     * which companies it may reach.
     *
     * <h3>What this catches</h3>
     * Absence of a company scope means unrestricted, and it has to — a role nobody configured must keep
     * working, or shipping the axis would blank every existing screen. The consequence is that "I forgot
     * to restrict the companies" and "I meant all companies" are the same payload, and the first one
     * hands a regional HR every region's departments and headcount reports with nothing on screen saying
     * so. Nothing downstream can tell them apart, so the distinction has to be demanded here, once, from
     * the person who knows the answer.
     *
     * <h3>Why {@code ALL} specifically, and not "touches a multi-company model"</h3>
     * A self-service employee role reaches multi-company models too, and must stay configurable with no
     * company scope at all: its row scope is {@code SELF}, which resolves to the caller's own record, and
     * the two compose as AND — so an unrestricted company axis widens nothing. Demanding a company scope
     * from every role that merely touches such a model would block exactly the role that needs no
     * company at all, which is the common case. It is the broad row scope that makes the missing company
     * scope dangerous, so that is the pair this looks for.
     *
     * <p>Deliberately server-side even though the wizard can warn: the wizard is one client of this
     * endpoint, and a payload assembled anywhere else must not be able to grant a wider role than the UI
     * permits.
     */
    private void rejectUnboundedCompanyScope(JsonNode rowsJson) {
        if (hasCompanyScope(rowsJson)) {
            return;
        }
        for (JsonNode row : rowsJson) {
            JsonNode modelNode = row.get("model");
            if (modelNode == null || !modelNode.isString()) {
                continue;
            }
            String model = modelNode.asString();
            if (!ModelManager.existModel(model) || !ModelManager.getModel(model).isMultiCompany()) {
                continue;
            }
            if (grantsEveryRow(row.get("dataScopes"))) {
                throw new BusinessException("This role may read every record of {0}, and records of {0} "
                        + "belong to one company. Select the legal entities the role may reach, or narrow "
                        + "its record scope.", ModelManager.getModel(model).getLabel());
            }
        }
    }

    /** True when the payload configures the company dimension at all — see {@code COMPANY_MODEL}. */
    private static boolean hasCompanyScope(JsonNode rowsJson) {
        for (JsonNode row : rowsJson) {
            JsonNode modelNode = row.get("model");
            if (modelNode != null && modelNode.isString()
                    && ModelConstant.COMPANY_MODEL.equals(modelNode.asString())) {
                JsonNode scopes = row.get("dataScopes");
                return scopes != null && scopes.isArray() && !scopes.isEmpty();
            }
        }
        return false;
    }

    /**
     * True when any rule grants every row. Matches the enum <b>name</b> because that is the wire form
     * the snapshot reads back ({@code ScopeType.valueOf}); permission-starter is not on this module's
     * classpath, so the constant cannot be shared and the spelling is asserted by a test instead.
     */
    private static boolean grantsEveryRow(JsonNode dataScopes) {
        if (dataScopes == null || !dataScopes.isArray()) {
            // No rule for a model the role can reach is the same statement as ALL: the snapshot finds
            // nothing to narrow with and the read goes unbounded.
            return true;
        }
        if (dataScopes.isEmpty()) {
            return true;
        }
        for (JsonNode rule : dataScopes) {
            JsonNode type = rule.get("scopeType");
            if (type != null && type.isString() && SCOPE_TYPE_ALL.equals(type.asString())) {
                return true;
            }
        }
        return false;
    }

    /** Writes one role_sensitive_field_set row per granted setId (de-duped).
     *  Payload is a flat JSON string array (role-wide) — each SFS carries its
     *  own canonical model, so no model is stored here. */
    private void writeRoleSensitiveFieldSets(Long roleId, JsonNode idsJson) {
        java.util.Set<String> setIds = new java.util.LinkedHashSet<>(JsonArrayUtils.toStringList(idsJson));
        if (setIds.isEmpty()) return;
        List<RoleSensitiveFieldSet> rows = new ArrayList<>(setIds.size());
        for (String sid : setIds) {
            if (sid == null || sid.isBlank()) continue;
            RoleSensitiveFieldSet r = new RoleSensitiveFieldSet();
            r.setRoleId(roleId);
            r.setSensitiveFieldSetId(sid);
            rows.add(r);
        }
        if (!rows.isEmpty()) roleSensitiveFieldSetService.createList(rows);
    }

}
