package io.softa.starter.user.provisioning;

import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.context.UserInfo;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.TenantInfoService;
import io.softa.starter.user.constant.RoleConstant;
import io.softa.starter.user.entity.Role;
import io.softa.starter.user.entity.UserRoleRel;
import io.softa.starter.user.enums.UserRoleSource;
import io.softa.starter.user.service.RoleService;
import io.softa.starter.user.service.UserAccountService;
import io.softa.starter.user.service.UserInvitationService;
import io.softa.starter.user.service.UserRoleRelService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import static io.softa.framework.base.context.ContextUtils.inTenantContext;

/**
 * Creates a tenant's first admin — a reusable user-starter feature. Runs entirely inside the target
 * tenant's context (permission skipped), so account, profile and role-grant all land under the right
 * tenant:
 * <ol>
 *   <li>reject a duplicate email within the tenant;</li>
 *   <li>register an INVITED {@code UserAccount} (+ profile) with NO password;</li>
 *   <li>grant the seeded {@code TENANT_ADMIN} role;</li>
 *   <li>email a set-password invitation so the admin activates the account themselves;</li>
 *   <li>publish {@link AdminProvisionedEvent} so business modules can attach their own record
 *       (e.g. corehr builds an {@code Employee} bound to the account) — user-starter stays ⊥ to them.</li>
 * </ol>
 * Takes the target tenant as a bare id ({@code request.tenantId}) — it wires user-starter services to
 * a tenant chosen by the caller and carries no dependency on tenant-starter (user ⊥ tenant).
 */
@Slf4j
@Service
public class AdminProvisioningService {

    private final UserAccountService accountService;
    private final RoleService roleService;
    private final UserRoleRelService userRoleRelService;
    private final UserInvitationService invitationService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Optional (same as {@code LoginServiceImpl}): absent in a single-tenant deployment or one without
     * tenant-starter, where there is no provisioning axis and the gate below does not apply.
     */
    @Autowired(required = false)
    private TenantInfoService tenantInfoService;

    public AdminProvisioningService(UserAccountService accountService,
                                    RoleService roleService,
                                    UserRoleRelService userRoleRelService,
                                    UserInvitationService invitationService,
                                    ApplicationEventPublisher eventPublisher) {
        this.accountService = accountService;
        this.roleService = roleService;
        this.userRoleRelService = userRoleRelService;
        this.invitationService = invitationService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(rollbackFor = Exception.class)
    public CreateAdminResult createAdmin(CreateAdminRequest request) {
        Assert.notNull(request, "request must not be null");
        Assert.notNull(request.getTenantId(), "tenantId must not be null");
        Assert.hasText(request.getEmail(), "email must not be blank");
        // Mobile is mandatory: it becomes the linked employee's contact phone (business modules that build
        // a personnel record off the admin — e.g. corehr — need a phone, and the profile field is required).
        Assert.hasText(request.getMobile(), "mobile must not be blank");

        // Readiness gate, and it runs BEFORE any write.
        //
        // An admin may only be created for a tenant that finished provisioning. This is stricter than the
        // TENANT_ADMIN-role check below, which is the precise prerequisite for *this* method: until every
        // seeder has reported, the roles an admin is granted, and the masters a personnel record refers to,
        // may not exist yet.
        //
        // Waiting costs little (seeding is seconds), and ops already had to wait for the role seed.
        if (tenantInfoService != null && !tenantInfoService.isTenantProvisioned(request.getTenantId())) {
            throw new BusinessException("Tenant " + request.getTenantId()
                    + " is still being set up (or its setup failed) — create the admin once it is ready.");
        }

        // The inviter is the current (Ops) user — captured before switching into the tenant context.
        Long inviter = ContextHolder.getContext() == null ? null : ContextHolder.getContext().getUserId();

        // No cross-tenant email check here any more. It used to run BEFORE pinning into the target
        // tenant precisely so it would see other tenants — which is the rule that made a person who
        // already works somewhere unable to be this tenant's admin, the one case where "the same
        // address in a second company" is the intended outcome rather than a collision.
        // registerInvitedUser now owns both halves: it links to the existing person instead of
        // minting a rival, and still refuses a duplicate WITHIN this tenant.

        return inTenantContext(request.getTenantId(), () -> {
            // INVITED account (no password) — the admin sets their own password via the invitation.
            // No display name captured at admin-provisioning time → null falls back to the email.
            UserInfo user = accountService.registerInvitedUser(request.getEmail(), request.getMobile(), null);

            // Belt and braces behind the READY gate above, which already implies this: READY means every
            // expected seeder is done, and TENANT_ADMIN is seeded by pre-data. Kept because the implication
            // holds only as long as `expected-seeders` actually lists the seeder that creates roles — drop
            // pre-data from that config and READY would stop meaning "roles exist". Granting an admin with no
            // role is worse than a clear error, so this stays as the precise last check.
            Role adminRole = roleService.searchOne(new Filters().eq(Role::getCode, RoleConstant.CODE_TENANT_ADMIN))
                    .orElseThrow(() -> new BusinessException(
                            "TENANT_ADMIN role not yet seeded for tenant " + request.getTenantId()
                            + " — the tenant is still initializing (per-tenant roles seed asynchronously); retry shortly."));

            UserRoleRel grant = new UserRoleRel();
            // tenant_id auto-stamped by the framework (UserRoleRel is multiTenant, inside inTenantContext).
            grant.setUserId(user.getUserId());
            grant.setRoleId(adminRole.getId());
            grant.setSource(UserRoleSource.MANUAL);
            userRoleRelService.createOne(grant);

            // Email the set-password invitation. A tenant is not refused its first admin because no
            // mail can go out yet: the invitation is kept and can be sent again once one can.
            invitationService.inviteWithoutDeliveryCheck(user.getUserId(), inviter);

            // Announce the new admin so business modules can attach their own record (e.g. corehr builds an
            // Employee bound to this account). Fired in-tx; the AFTER_COMMIT MQ bridge means a rolled-back
            // creation never publishes. user-starter carries no dependency on those modules (⊥).
            eventPublisher.publishEvent(new AdminProvisionedEvent(
                    request.getTenantId(), user.getUserId(), request.getEmail(), request.getMobile()));

            log.info("Invited tenant-admin userId={} email={} for tenant {}",
                    user.getUserId(), request.getEmail(), request.getTenantId());
            return new CreateAdminResult(user.getUserId(), request.getEmail());
        });
    }
}
