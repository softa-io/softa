package io.softa.starter.user.service;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.constant.RoleConstant;
import io.softa.starter.user.entity.Role;
import io.softa.starter.user.entity.UserRoleRel;
import io.softa.framework.base.enums.BuiltinRole;

/**
 * The platform super-admin's cross-tenant account roster: who it may reach, and the window that lets
 * it reach them.
 *
 * <p>Extracted from {@code UserAccountController} so every endpoint that opens a row belonging to
 * another tenant answers to the SAME bounds. That identity matters more than the deduplication: the
 * account detail page opens through {@code /UserAccount/getById}, which is bounded here, while the
 * panels on that page call their own endpoints. If one of them computed its own bounds the two would
 * drift, producing either "the page opens but a panel says it cannot load" or, worse, a panel that
 * reads a user the page itself is not allowed to open.
 *
 * <p>The roster is: every account holding {@code SUPER_ADMIN} or {@code TENANT_ADMIN} in ANY tenant,
 * plus every account in the super-admin's own (platform) tenant.
 */
@Component
@RequiredArgsConstructor
public class UserRosterScope {

    /** Roles whose holders make up the platform super-admin's account roster. */
    private static final List<String> ADMIN_ROLE_CODES =
            List.of(RoleConstant.CODE_TENANT_ADMIN, RoleConstant.CODE_SUPER_ADMIN);

    private final RoleService roleService;
    private final UserRoleRelService userRoleRelService;

    /** True when the caller holds the platform super-admin role. */
    public boolean isPlatformSuperAdmin() {
        Context context = ContextHolder.getContext();
        Set<String> roleCodes = context == null ? null : context.getRoleCodes();
        return BuiltinRole.SUPER_ADMIN.heldBy(roleCodes);
    }

    /**
     * Run a roster read inside a cross-tenant window — but only for the platform super-admin, whose
     * account list is defined as spanning tenants.
     *
     * <p>Opened by hand rather than with {@code @CrossTenant} for two reasons. The annotation applies
     * to every caller, so it would waive tenant isolation on the endpoint for ordinary tenant users
     * too; and it additionally skips permission checks, which these reads have no reason to do.
     *
     * <p>Both the scope computation and the query must sit inside the window. {@link
     * #scopeToAdminAccounts} resolves the roster through {@code Role} and {@code UserRoleRel}, which
     * are multiTenant, so a tenant-filtered lookup would only find this tenant's admin roles and the
     * roster would collapse to the caller's own tenant. And the outer read has to be unfiltered as
     * well: the ORM would otherwise AND {@code tenant_id = own} onto {@code roster OR tenant_id = own},
     * reducing it to {@code tenant_id = own} — the roster silently dropped. The scope filter is what
     * bounds the result; the window only stops the ORM from bounding it twice, and more narrowly than
     * intended.
     */
    public <T> T call(Supplier<T> read) {
        if (!isPlatformSuperAdmin()) {
            return read.get();
        }
        Context crossTenant = ContextHolder.cloneContext();
        crossTenant.setCrossTenant(true);
        return ContextHolder.callWith(crossTenant, read::get);
    }

    /**
     * Re-scope a UserAccount list read. {@code UserAccount} is framework-multiTenant, so a normal
     * tenant user's reads are auto-filtered to their tenant by the ORM (nothing to add) and a generic
     * cross-tenant/system caller sees everything. Only the platform super-admin needs custom scoping.
     * Single-tenant deployments are untouched.
     */
    public Filters scopeByTenant(Filters filters) {
        // Consultant memberships are hidden from every roster read, before anything else narrows.
        // A tenant does not administer them: they are minted, dated and revoked by the platform, and
        // a tenant admin who could see one could try to freeze or off-board it, which would leave the
        // platform's grant saying yes while the membership said no. Applied HERE rather than at each
        // endpoint because that is the property that matters — a roster query added next year is
        // covered without its author knowing consultants exist.
        Filters scoped = hideConsultants(filters);
        if (!SystemConfig.env.isEnableMultiTenancy()) {
            return scoped;   // single-tenant: no tenant dimension
        }
        if (!isPlatformSuperAdmin()) {
            return scoped;   // non-super-admin: the ORM already auto-filters reads to the caller's tenant
        }
        return scopeToAdminAccounts(scoped);
    }

    /**
     * Exclude consultant memberships from a roster read.
     *
     * <p>Matches rows where the flag is false OR unset: every membership that existed before
     * consultants did carries null there, and a bare {@code eq(false)} would hide the entire
     * existing roster the moment this shipped.
     */
    private Filters hideConsultants(Filters filters) {
        Filters base = filters == null ? new Filters() : filters;
        return base.and(Filters.or()
                .eq(UserAccount::getConsultant, false)
                .isNotSet(UserAccount::getConsultant));
    }

    /**
     * The platform super-admin's account roster as a filter: every account holding a
     * {@code SUPER_ADMIN} or {@code TENANT_ADMIN} role across all tenants, PLUS every account in the
     * super-admin's own (platform) tenant. Resolved via grants (admin role codes →
     * {@code user_role_rel} → user ids).
     *
     * <p>Must be called inside {@link #call} — {@code Role} and {@code UserRoleRel} are multiTenant,
     * so without that window both lookups see only this tenant's admin roles and the roster
     * degenerates to the caller's own tenant.
     */
    public Filters scopeToAdminAccounts(Filters filters) {
        List<Long> adminRoleIds = roleService.searchList(new Filters().in(Role::getCode, ADMIN_ROLE_CODES))
                .stream().map(Role::getId).toList();
        List<Long> userIds = adminRoleIds.isEmpty() ? List.of()
                : userRoleRelService.searchList(new Filters().in(UserRoleRel::getRoleId, adminRoleIds))
                        .stream().map(UserRoleRel::getUserId).distinct().toList();
        // Accounts holding an admin role (empty → sentinel -1L, avoids an ill-defined empty IN)...
        Filters roster = new Filters().in(ModelConstant.ID, userIds.isEmpty() ? List.of(-1L) : userIds);
        // ...OR any account in the super-admin's own (platform) tenant.
        Long ownTenant = ContextHolder.getContext() == null ? null : ContextHolder.getContext().getTenantId();
        Filters scope = ownTenant == null ? roster
                : Filters.or(roster, new Filters().eq(ModelConstant.TENANT_ID, ownTenant));
        return filters == null ? scope : Filters.and(filters, scope);
    }
}
