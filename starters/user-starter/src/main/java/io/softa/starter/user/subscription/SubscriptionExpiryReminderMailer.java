package io.softa.starter.user.subscription;

import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.message.MessageScope;
import io.softa.framework.base.message.SubscriptionExpiryReminderMessage;
import io.softa.framework.orm.domain.Filters;
import io.softa.starter.message.mail.dto.SendMailDTO;
import io.softa.starter.message.service.MessageService;
import io.softa.starter.user.constant.RoleConstant;
import io.softa.starter.user.entity.Role;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.entity.UserRoleRel;
import io.softa.starter.user.service.RoleService;
import io.softa.starter.user.service.UserAccountService;
import io.softa.starter.user.service.UserRoleRelService;

/**
 * Emails a subscription-expiry reminder to every {@code TENANT_ADMIN} of a tenant. Driven by
 * {@link SubscriptionExpiryReminderConsumer} off the tenant-starter reminder MQ message (which keeps
 * tenant-starter ⊥ user-starter). Sends one templated mail per admin through {@link MessageService}, so
 * no admin sees the others' addresses and each delivery has its own send record (the same reason
 * {@code UserInvitationService} sends per recipient); message-starter renders the template when it
 * delivers it.
 *
 * <p>Must run inside the tenant's context ({@code inTenantContext}) so the multi-tenant {@code Role} /
 * {@code UserRoleRel} lookups resolve to this tenant's admins only. The mail template
 * ({@value #TEMPLATE_EXPIRY_REMINDER}) is a platform-tier row rendered under PLATFORM scope, so every
 * tenant-specific value is passed as a template variable.
 */
@Slf4j
@Service
public class SubscriptionExpiryReminderMailer {

    /** Platform ({@code tenantId=0}) mail template codes, seeded in {@code MailTemplate.System.json}.
     *  A purchased plan gets renewal wording; a trial gets upgrade wording. */
    static final String TEMPLATE_EXPIRY_REMINDER = "subscription.expiry-reminder";
    static final String TEMPLATE_TRIAL_EXPIRY_REMINDER = "subscription.trial-expiry-reminder";
    /**
     * Used when a later period exists but does not start the day after this one ends. "Please renew" is the
     * wrong ask — they have renewed — so this template names the uncovered stretch instead.
     */
    static final String TEMPLATE_GAP_REMINDER = "subscription.gap-reminder";
    /**
     * Used when a LOWER-tier period covers the day after this one ends — the tenant is not cut off, it drops to
     * that plan. Every other template promises a loss of access, which here would simply be false: each tenant
     * owns an open-ended floor-plan period underneath whatever it bought, so a purchased period lapsing means a
     * downgrade, not a lockout. Says which plan takes over and what buying again would keep.
     */
    static final String TEMPLATE_DOWNGRADE_REMINDER = "subscription.downgrade-reminder";

    private final RoleService roleService;
    private final UserRoleRelService userRoleRelService;
    private final UserAccountService accountService;
    private final MessageService messageService;

    public SubscriptionExpiryReminderMailer(RoleService roleService,
                                            UserRoleRelService userRoleRelService,
                                            UserAccountService accountService,
                                            MessageService messageService) {
        this.roleService = roleService;
        this.userRoleRelService = userRoleRelService;
        this.accountService = accountService;
        this.messageService = messageService;
    }

    /** Send one reminder mail per {@code TENANT_ADMIN}. Assumes it already runs in the tenant's context. */
    public void remindAdmins(SubscriptionExpiryReminderMessage message) {
        Role adminRole = roleService.searchOne(
                new Filters().eq(Role::getCode, RoleConstant.CODE_TENANT_ADMIN)).orElse(null);
        if (adminRole == null) {
            log.warn("TENANT_ADMIN role not found for tenant {}; skipping expiry reminder", message.tenantId());
            return;
        }
        List<Long> adminUserIds = userRoleRelService.getDistinctFieldValue(
                UserRoleRel::getUserId, new Filters().eq(UserRoleRel::getRoleId, adminRole.getId()));
        if (adminUserIds.isEmpty()) {
            log.warn("No TENANT_ADMIN users for tenant {}; skipping expiry reminder", message.tenantId());
            return;
        }
        List<String> emails = accountService.getByIds(adminUserIds).stream()
                .map(UserAccount::getEmail)
                .filter(email -> email != null && !email.isBlank())
                .distinct()
                .toList();
        if (emails.isEmpty()) {
            log.warn("TENANT_ADMIN users for tenant {} have no email; skipping expiry reminder", message.tenantId());
            return;
        }

        Map<String, Object> variables = Map.of(
                "tenantName", message.tenantName() == null ? "" : message.tenantName(),
                "planId", message.planId() == null ? "" : message.planId(),
                "expiryDate", message.effectiveTo() == null ? "" : message.effectiveTo(),
                "daysLeft", message.daysLeft(),
                "nextStartDate", message.nextStartDate() == null ? "" : message.nextStartDate(),
                "successorPlanId", message.successorPlanId() == null ? "" : message.successorPlanId());
        // Four mutually exclusive asks, ordered by what the reader actually loses.
        //
        // The downgrade case is FIRST and outranks everything, because it is the only one where access does
        // not stop: a lower-tier period covers the day after, so the tenant keeps working with fewer modules.
        // Sending any of the other three here would tell a paying customer they are about to lose access when
        // they are not — and a customer who discovers the claim was false stops believing the next reminder.
        //
        // The gap case is next and wins over `trial`, because what matters is the uncovered stretch, not which
        // kind of period is ending: a trial that ends with a paid period booked for later still leaves the
        // customer with nothing in between, and "upgrade to keep access" would read as though nothing had
        // been bought.
        String templateCode;
        if (message.successorPlanId() != null && !message.successorPlanId().isBlank()) {
            templateCode = TEMPLATE_DOWNGRADE_REMINDER;
        } else if (message.nextStartDate() != null && !message.nextStartDate().isBlank()) {
            templateCode = TEMPLATE_GAP_REMINDER;
        } else if (message.trial()) {
            templateCode = TEMPLATE_TRIAL_EXPIRY_REMINDER;
        } else {
            templateCode = TEMPLATE_EXPIRY_REMINDER;
        }
        int queued = 0;
        for (String email : emails) {
            // Platform-owned mail about the tenant's contract: the PLATFORM tier
            // renders it (a tenant must not re-word its own expiry notices) and
            // the platform quota bucket absorbs it; the send record lands in the
            // tenant's books, since this runs in the tenant's context.
            try {
                messageService.sendMail(SendMailDTO.ofTemplate(List.of(email), templateCode, variables,
                        MessageScope.PLATFORM));
                queued++;
            } catch (BusinessException e) {
                // One admin's malformed address does not cost the others their reminder.
                log.error("Reminder mail for tenant {} not sent: {}", message.tenantId(), e.getMessage());
            }
        }
        // The template code itself, not a re-derivation of it: a two-branch guess here logged a gap reminder
        // as "subscription-expiry", so the log disagreed with the mail that was actually sent — exactly the
        // wrong signal when someone is investigating why a customer got the wording they did.
        log.info("Queued {} '{}' reminder mail(s) for tenant {} ({} day(s) left, expires {})",
                queued, templateCode,
                message.tenantId(), message.daysLeft(), message.effectiveTo());
    }
}
