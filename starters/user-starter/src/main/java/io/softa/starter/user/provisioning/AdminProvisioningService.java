package io.softa.starter.user.provisioning;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import static io.softa.framework.base.context.ContextUtils.inTenantContext;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.context.UserInfo;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.domain.Filters;
import io.softa.starter.user.constant.RoleConstant;
import io.softa.starter.user.entity.Role;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.entity.UserRoleRel;
import io.softa.starter.user.enums.UserRoleSource;
import io.softa.starter.user.service.RoleService;
import io.softa.starter.user.service.UserAccountService;
import io.softa.starter.user.service.UserInvitationService;
import io.softa.starter.user.service.UserRoleRelService;

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

        // The inviter is the current (Ops) user — captured before switching into the tenant context.
        Long inviter = ContextHolder.getContext() == null ? null : ContextHolder.getContext().getUserId();

        // email is globally unique: check across ALL tenants BEFORE pinning into the target tenant
        // (getUserByEmail is @CrossTenant). Inside inTenantContext the check would only see the
        // target tenant and miss an email already taken by another tenant.
        if (accountService.getUserByEmail(request.getEmail()).isPresent()) {
            throw new BusinessException("Email already exists: " + request.getEmail());
        }

        return inTenantContext(request.getTenantId(), () -> {
            // INVITED account (no password) — the admin sets their own password via the invitation.
            // No display name captured at admin-provisioning time → null falls back to the email.
            UserInfo user = accountService.registerInvitedUser(request.getEmail(), request.getMobile(), null);

            // Readiness gate for createAdmin. TENANT_ADMIN is seeded as part of the tenant's per-tenant
            // pre-data, which (since mode-2 Step 3) loads asynchronously. Its presence is the *precise*
            // prerequisite here — createAdmin needs the role, not the whole tenant READY (org masters are
            // the async AdminEmployeeSeeder's concern, and it self-retries on its own dependency). If the
            // role isn't seeded yet the tenant is still initializing: fail clearly so the caller retries,
            // rather than granting an admin with no role.
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

            // Email the set-password invitation.
            invitationService.invite(user.getUserId(), inviter);

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
