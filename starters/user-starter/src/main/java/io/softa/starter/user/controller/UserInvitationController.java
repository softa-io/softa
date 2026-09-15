package io.softa.starter.user.controller;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Page;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.web.dto.QueryParams;
import io.softa.framework.web.dto.SearchListParams;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.user.constant.RoleConstant;
import io.softa.starter.user.entity.Role;
import io.softa.starter.user.entity.UserRoleRel;
import io.softa.starter.user.service.RoleService;
import io.softa.starter.user.service.UserRoleRelService;
import io.softa.framework.base.enums.BuiltinRole;

/**
 * Shadows the generic {@code /UserInvitation} list reads so the platform super-admin can see the
 * invitations it issued.
 *
 * <p>{@code UserInvitation} is multiTenant and an invitation is written pinned to the <b>invited
 * account's</b> tenant, so it lands in the customer's tenant, not the platform one. The super-admin
 * browses from the platform tenant, so the ORM's automatic narrowing returned nothing at all — every
 * admin invitation it had just sent was invisible on its own console.
 *
 * <p>The fix is deliberately not "super-admin sees everything". Reaching across tenants is a property
 * of an <i>operation</i>, not of an identity — {@code PermissionInterceptor} says so explicitly and
 * pointedly does not set {@code crossTenant} for the super-admin. So the widening is opened here, for
 * this read, and bounded by a named scope: the same admin roster {@code UserAccountController} already
 * uses for the account list. Ops sees the admin invitations across tenants plus everything in its own
 * tenant — not a customer's invitations to their own staff, which are that customer's business.
 *
 * <p>A tenant admin is untouched: it keeps seeing exactly its own tenant's invitations, which is what
 * the ORM already does.
 */
@Tag(name = "User Invitation")
@RestController
@RequestMapping("/UserInvitation")
public class UserInvitationController {

    private static final String MODEL = "UserInvitation";
    /** Holding either of these makes an account part of the roster Ops is responsible for. */
    private static final List<String> ADMIN_ROLE_CODES =
            List.of(RoleConstant.CODE_SUPER_ADMIN, RoleConstant.CODE_TENANT_ADMIN);

    private final ModelService<Long> modelService;
    private final RoleService roleService;
    private final UserRoleRelService userRoleRelService;

    public UserInvitationController(ModelService<Long> modelService, RoleService roleService,
                                    UserRoleRelService userRoleRelService) {
        this.modelService = modelService;
        this.roleService = roleService;
        this.userRoleRelService = userRoleRelService;
    }

    @Operation(summary = "Search UserInvitation page — tenant-scoped (super-admin also sees the admin roster)")
    @PostMapping("/searchPage")
    public ApiResponse<Page<Map<String, Object>>> searchPage(@RequestBody(required = false) QueryParams queryParams) {
        QueryParams params = queryParams == null ? new QueryParams() : queryParams;
        FlexQuery flexQuery = QueryParams.convertParamsToFlexQuery(params);
        Page<Map<String, Object>> page = Page.of(params.getPageNumber(), params.getPageSize());
        return ApiResponse.success(inRosterScope(() -> {
            flexQuery.setFilters(scopeByTenant(flexQuery.getFilters()));
            return modelService.searchPage(MODEL, flexQuery, page);
        }));
    }

    @Operation(summary = "Search UserInvitation list — same scoping as searchPage")
    @PostMapping("/searchList")
    public ApiResponse<List<Map<String, Object>>> searchList(
            @RequestBody(required = false) SearchListParams searchListParams) {
        SearchListParams params = searchListParams == null ? new SearchListParams() : searchListParams;
        FlexQuery flexQuery = SearchListParams.convertParamsToFlexQuery(params);
        return ApiResponse.success(inRosterScope(() -> {
            flexQuery.setFilters(scopeByTenant(flexQuery.getFilters()));
            return modelService.searchList(MODEL, flexQuery);
        }));
    }

    /** True when the caller holds the platform super-admin role. */
    private static boolean isPlatformSuperAdmin() {
        Context context = ContextHolder.getContext();
        Set<String> roleCodes = context == null ? null : context.getRoleCodes();
        return BuiltinRole.SUPER_ADMIN.heldBy(roleCodes);
    }

    /**
     * Run the read in a cross-tenant window — super-admin only.
     *
     * <p>Both the roster lookup and the query itself have to sit inside it. {@code Role} and
     * {@code UserRoleRel} are multiTenant, so resolving the roster outside would find only the platform
     * tenant's admin roles and collapse to nothing; and the outer read has to be unnarrowed too, or the
     * ORM ANDs {@code tenant_id = platform} onto {@code roster OR tenant_id = platform} and silently
     * drops the roster half. The scope filter is what bounds the result — the window only stops the ORM
     * from bounding it a second time, more narrowly than intended.
     *
     * <p>Not {@code @CrossTenant}: that annotation applies to every caller (waiving isolation for tenant
     * users on the same endpoint) and additionally skips permission checks, which this read has no
     * reason to do.
     */
    private <T> T inRosterScope(Supplier<T> read) {
        if (!isPlatformSuperAdmin()) {
            return read.get();
        }
        Context crossTenant = ContextHolder.cloneContext();
        crossTenant.setCrossTenant(true);
        return ContextHolder.callWith(crossTenant, read::get);
    }

    private Filters scopeByTenant(Filters filters) {
        if (!SystemConfig.env.isEnableMultiTenancy()) {
            return filters;   // single-tenant: no tenant dimension
        }
        if (!isPlatformSuperAdmin()) {
            return filters;   // the ORM already narrows this caller's reads to its own tenant
        }
        return scopeToAdminInvitations(filters);
    }

    /**
     * Invitations addressed to an account holding an admin role in any tenant, plus every invitation in
     * the super-admin's own tenant.
     *
     * <p>Mirrors {@code UserAccountController.scopeToAdminAccounts} on purpose: the two pages answer the
     * same question about the same people, so "which admins is Ops responsible for" must not have two
     * definitions that can drift.
     */
    private Filters scopeToAdminInvitations(Filters filters) {
        List<Long> adminRoleIds = roleService.searchList(new Filters().in(Role::getCode, ADMIN_ROLE_CODES))
                .stream().map(Role::getId).toList();
        List<Long> adminUserIds = adminRoleIds.isEmpty() ? List.of()
                : userRoleRelService.searchList(new Filters().in(UserRoleRel::getRoleId, adminRoleIds))
                        .stream().map(UserRoleRel::getUserId).distinct().toList();
        // Empty → sentinel -1L rather than an empty IN, which is ill-defined.
        Filters roster = new Filters()
                .in("userId", adminUserIds.isEmpty() ? List.of(-1L) : adminUserIds);
        Long ownTenant = ContextHolder.getContext() == null ? null : ContextHolder.getContext().getTenantId();
        Filters scope = ownTenant == null ? roster
                : Filters.or(roster, new Filters().eq(ModelConstant.TENANT_ID, ownTenant));
        return filters == null ? scope : Filters.and(filters, scope);
    }
}
