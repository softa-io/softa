package io.softa.starter.user.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.CacheService;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.user.constant.RoleConstant;
import io.softa.starter.user.dto.EffectivePermissionsView;
import io.softa.starter.user.dto.UiContext;
import io.softa.starter.user.dto.UserRef;
import io.softa.starter.user.service.UserRosterScope;
import io.softa.starter.user.service.impl.UiContextBuilder;
import io.softa.starter.user.util.ModelRefIds;
import io.softa.starter.user.util.PermissionSnapshotKey;
import io.softa.framework.base.enums.BuiltinRole;

/**
 * Read-only admin API for the user-access (RBAC) management UI — the endpoints
 * the FE {@code user/access} section calls that are NOT plain entity CRUD.
 * Served under {@code /userAccess} (renamed from the generic {@code /admin}; the
 * whole surface — FE {@code src/app/user/access} + this {@code user-starter}
 * controller — is user-module scoped). Sibling {@code NavigationConfigOptionsController}
 * shares the prefix for the role-wizard option endpoints.
 *
 * <h3>{@code GET /userAccess/userRefs}</h3>
 * {@link UserRef} rows for the Add-Members / AssignRoles dialogs: the UserAccount
 * auth identity plus its Organizational identity (employeeId / departmentId /
 * legalEntityId) when the user is linked to an Employee — joined on the BE with
 * one extra IN query so the dialog classifies role-compatibility correctly
 * (reading UserAccount directly made every user look "pure"). Two indexed
 * queries, ~10ms typical. Rows are read as {@code Map} (not the entity overload)
 * to sidestep a JsonNode/Long conversion bug on snowflake audit-id columns.
 *
 * <h3>{@code GET /userAccess/userEffectivePermissions}</h3>
 * The effective permission snapshot for an ARBITRARY user, read straight from the
 * shared cache as raw JSON (the permission engine is the sole builder; it warms a
 * user's entry on that user's own authenticated requests). {@code GET /me/uiContext}
 * serves the CURRENT user the same way. A target user who has not been active since
 * the last cache expiry returns {@code null} here — an accepted degradation for this
 * admin view. The key is scoped by the caller's tenantId (no cross-tenant leak).
 *
 * <h3>Org identity</h3>
 * The employeeId / departmentId / legalEntityId columns come from reading the
 * {@code Employee} model directly (约定读). {@code /userAccess/*} is super-admin
 * only, so the read isn't scope-filtered and needs no permission bypass; a
 * deployment with no {@code Employee} model degrades to pure UserAccount rows.
 */
@Slf4j
@Tag(name = "User Access")
@RestController
@RequestMapping("/userAccess")
@RequiredArgsConstructor
public class UserAccessController {

    /** Max UserAccount rows returned — matches the dialog's client-side
     *  pagination so the BE never silently truncates. */
    private static final int USER_PAGE_CAP = 1000;

    private final ModelService<?> modelService;
    private final CacheService cacheService;
    private final UiContextBuilder uiContextBuilder;
    private final UserRosterScope rosterScope;

    // ─────────────────────── user refs (member / assign dialogs) ───────────────────────

    @GetMapping("/userRefs")
    @Operation(summary = "List user refs (UserAccount + org identity) for admin dialogs")
    public ApiResponse<List<UserRef>> listUserRefs() {
        FlexQuery q = new FlexQuery();
        q.setLimitSize(USER_PAGE_CAP);
        q.setFields(List.of(
                "id", "nickname", "username", "email", "mobile",
                "status", "createdTime", "updatedTime"));
        // Through the roster scope, like every other UserAccount roster read. This one was reading
        // the model raw, which is how consultant memberships — hidden from the User Accounts page
        // since they were introduced — still turned up in the Add-Members and Assign-Roles dialogs.
        // A tenant does not administer its consultants, and offering one as a candidate invites an
        // administrator to grant a role to somebody they cannot even see.
        //
        // Routing it here rather than filtering consultants out on the spot is the point of that
        // class: the dialogs and the page they open from now compute their bounds from one place,
        // which is what stops a panel from listing a user the page itself will not open.
        List<Map<String, Object>> users = rosterScope.call(() -> {
            q.setFilters(rosterScope.scopeByTenant(q.getFilters()));
            return modelService.searchList("UserAccount", q);
        });

        Map<Long, EmployeeOrgView> ctxByUser = loadOrgContext(users);

        List<UserRef> out = new ArrayList<>(users.size());
        for (Map<String, Object> u : users) {
            Long id = ModelRefIds.extractLongId(u.get("id"));
            EmployeeOrgView ctx = ctxByUser.get(id);
            out.add(new UserRef(
                    id,
                    asString(u.get("nickname")),
                    asString(u.get("username")),
                    asString(u.get("email")),
                    asString(u.get("mobile")),
                    asString(u.get("status")),
                    asString(u.get("createdTime")),
                    asString(u.get("updatedTime")),
                    ctx == null ? null : ctx.getId(),
                    ctx == null ? null : ctx.getDepartmentId(),
                    ctx == null ? null : ctx.getCompanyId()));
        }
        return ApiResponse.success(out);
    }

    /** Resolve employeeId / departmentId / companyId per user by reading the
     *  {@code Employee} model directly, by convention rather than through a shared
     *  contract. Empty map when no {@code Employee}
     *  model exists (non-HR deployment) or on error — degrades to "every user is
     *  pure". {@code /userAccess/*} is super-admin only, so the read is not
     *  scope-filtered (super-admin bypasses) and needs no permission skip. */
    private Map<Long, EmployeeOrgView> loadOrgContext(List<Map<String, Object>> users) {
        if (users.isEmpty() || !ModelManager.existModel("Employee")) return Map.of();
        Set<Long> userIds = new HashSet<>(users.size());
        for (Map<String, Object> u : users) {
            Long id = ModelRefIds.extractLongId(u.get("id"));
            if (id != null) userIds.add(id);
        }
        if (userIds.isEmpty()) return Map.of();
        List<EmployeeOrgView> rows;
        try {
            rows = modelService.searchList("Employee",
                    new FlexQuery(List.of("userId", "id", "departmentId", "companyId"),
                            new Filters().in("userId", userIds)),
                    EmployeeOrgView.class);
        } catch (Throwable t) {
            log.warn("userRefs — Employee read failed; degrading to no org context", t);
            return Map.of();
        }
        Map<Long, EmployeeOrgView> out = new HashMap<>(rows.size());
        for (EmployeeOrgView e : rows) {
            if (e.getUserId() != null) out.put(e.getUserId(), e);
        }
        return out;
    }

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }

    /** {@code Employee} projection for org-identity enrichment. {@code id} is the
     *  employeeId; department / company may be null. Public + no-arg ctor so
     *  the ModelService Class projection (BeanTool) can populate it. */
    @Data
    public static class EmployeeOrgView {
        private Long userId;
        private Long id;
        private Long departmentId;
        private Long companyId;
    }

    // ─────────────────────── effective permissions (user detail view) ───────────────────────

    /**
     * One user's effective access, cache-first: the engine's live snapshot when it has one, a fresh
     * build when it does not.
     *
     * <p>Both halves used to be wrong. The key was built from {@code ContextHolder}'s tenant — the
     * CALLER's — while {@code userId} names the SUBJECT, and the engine writes each snapshot under the
     * subject's own tenant, so a platform super-admin inspecting another tenant's user could never
     * hit. And on a miss the endpoint simply answered null ("this endpoint no longer builds"), which
     * the panel rendered as a load failure — so the view was permanently blank for any user who had
     * not authenticated within the cache TTL, which is every freshly created account.
     *
     * <p>The subject's tenant is resolved through {@link UserRosterScope}, the same window and the
     * same bounds that let {@code /UserAccount/getById} open the detail page this panel sits on: a
     * platform super-admin reaches roster members across tenants, everyone else stays tenant-local
     * and can therefore only ever inspect their own tenant's users.
     *
     * <p>A rebuild is NOT written back to the engine's key. The engine reads that key as
     * {@code PermissionInfo}; a {@link UiContext}-shaped payload would deserialize with
     * {@code grantedCompanyIds} absent, silently disabling the company row-scope axis for that user
     * until the TTL expired. Rebuilding costs a handful of indexed reads on an admin's single page
     * open, which is not worth that risk.
     */
    @GetMapping("/userEffectivePermissions")
    @Operation(summary = "Effective access for a user — engine snapshot when cached, freshly built otherwise")
    public ApiResponse<EffectivePermissionsView> userEffectivePermissions(@RequestParam("userId") Long userId) {
        return ApiResponse.success(rosterScope.call(() -> {
            Long subjectTenantId = resolveSubjectTenantId(userId);
            if (subjectTenantId == null) {
                // Outside what this caller may read (another tenant's user, or no such account) —
                // same answer as a nonexistent record, mirroring getById's roster behaviour.
                return null;
            }
            JsonNode cached =
                    cacheService.get(PermissionSnapshotKey.forUser(subjectTenantId, userId), JsonNode.class);
            return cached != null ? fromSnapshot(cached)
                    : fromUiContext(uiContextBuilder.build(userId, subjectTenantId),
                            uiContextBuilder.modelScopeMapFor(userId));
        }));
    }

    /** The subject's OWN tenant — the half the old key got wrong. Runs inside the roster window, so a
     *  non-super-admin caller is tenant-filtered by the ORM and simply finds nothing for a user
     *  outside their tenant. */
    private Long resolveSubjectTenantId(Long userId) {
        List<Map<String, Object>> rows = modelService.searchList("UserAccount",
                new FlexQuery(List.of(ModelConstant.TENANT_ID), new Filters().eq(ModelConstant.ID, userId)));
        if (rows.isEmpty()) {
            return null;
        }
        Object tenantId = rows.get(0).get(ModelConstant.TENANT_ID);
        return tenantId instanceof Number n ? n.longValue() : null;
    }

    /** Cache hit — the engine's PermissionInfo JSON is a superset of this view (see
     *  {@link EffectivePermissionsView} on why unknown fields are tolerated). */
    private EffectivePermissionsView fromSnapshot(JsonNode snapshot) {
        EffectivePermissionsView view =
                JsonUtils.jsonNodeToObject(snapshot, EffectivePermissionsView.class);
        view.setSuperAdmin(holdsSuperAdmin(view.getRoleCodes()));
        view.setSource("cache");
        return view;
    }

    /** Cache miss — map the freshly built context onto the same shape. */
    private EffectivePermissionsView fromUiContext(UiContext ui, Map<String, List<JsonNode>> modelScopeMap) {
        EffectivePermissionsView view = new EffectivePermissionsView();
        view.setRoleCodes(ui.getRoleCodes());
        view.setNavigations(ui.getNavigations());
        view.setPermissions(ui.getPermissions());
        view.setModelSensitiveFieldSetsMap(ui.getModelSensitiveFieldSetsMap());
        view.setModelScopeMap(modelScopeMap);
        view.setSuperAdmin(holdsSuperAdmin(ui.getRoleCodes()));
        view.setSource("rebuilt");
        return view;
    }

    private static boolean holdsSuperAdmin(Set<String> roleCodes) {
        return BuiltinRole.SUPER_ADMIN.heldBy(roleCodes);
    }
}
