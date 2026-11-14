package io.softa.starter.user.subscription;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.message.MessageScope;
import io.softa.framework.base.message.SubscriptionExpiryReminderMessage;
import io.softa.framework.orm.domain.Filters;
import io.softa.starter.message.mail.dto.SendMailDTO;
import io.softa.starter.message.service.MessageService;
import io.softa.starter.user.entity.Role;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.service.RoleService;
import io.softa.starter.user.service.UserAccountService;
import io.softa.starter.user.service.UserRoleRelService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which reminder wording a tenant admin receives, and that each admin is mailed separately.
 *
 * <p>The three templates make three different asks, and picking the wrong one is worse than sending nothing.
 * "Renew before you lose access" to a customer who has already renewed reads as a billing error; "upgrade to
 * keep your trial features" to someone who bought a plan starting next month reads as the purchase not having
 * registered. Only the gap-versus-trial precedence encodes that, and it is a single {@code else if} away from
 * inverting silently — no test covered it until this class.
 */
class SubscriptionExpiryReminderMailerTest {

    private static final long TENANT = 1001L;
    private static final long ADMIN_ROLE_ID = 55L;

    private MessageService messageService;
    private UserAccountService accountService;
    private SubscriptionExpiryReminderMailer mailer;

    @BeforeEach
    void setUp() {
        RoleService roleService = mock(RoleService.class);
        UserRoleRelService userRoleRelService = mock(UserRoleRelService.class);
        accountService = mock(UserAccountService.class);
        messageService = mock(MessageService.class);

        Role adminRole = new Role();
        adminRole.setId(ADMIN_ROLE_ID);
        when(roleService.searchOne(any(Filters.class))).thenReturn(Optional.of(adminRole));
        when(userRoleRelService.getDistinctFieldValue(any(), any(Filters.class))).thenReturn(List.of(7L));
        when(accountService.getByIds(anyList())).thenReturn(List.of(account("admin@acme.test")));

        mailer = new SubscriptionExpiryReminderMailer(roleService, userRoleRelService, accountService,
                messageService);
    }

    // ─── which template ───

    @Test
    @DisplayName("a purchased plan with nothing after it — the plain renewal ask")
    void paidWithNoSuccessor_plainRenewal() {
        mailer.remindAdmins(message(false, null));

        assertThat(captureMail().getTemplateCode())
                .isEqualTo(SubscriptionExpiryReminderMailer.TEMPLATE_EXPIRY_REMINDER);
    }

    @Test
    @DisplayName("a trial with nothing after it — the upgrade ask")
    void trialWithNoSuccessor_upgradeAsk() {
        mailer.remindAdmins(message(true, null));

        assertThat(captureMail().getTemplateCode())
                .isEqualTo(SubscriptionExpiryReminderMailer.TEMPLATE_TRIAL_EXPIRY_REMINDER);
    }

    @Test
    @DisplayName("a gap after a purchased plan — the gap notice, not a renewal ask")
    void paidWithGap_gapNotice() {
        // They have renewed; the coverage just does not resume the day after. Asking them to renew would be
        // telling a paying customer their payment did not arrive.
        mailer.remindAdmins(message(false, "2026-10-01"));

        assertThat(captureMail().getTemplateCode())
                .isEqualTo(SubscriptionExpiryReminderMailer.TEMPLATE_GAP_REMINDER);
    }

    @Test
    @DisplayName("a gap after a trial — the gap notice wins over the upgrade ask")
    void trialWithGap_gapWinsOverTrial() {
        // The precedence this class exists for. What the reader needs to know is the uncovered stretch, not
        // which kind of period is ending — a trial that ends with a paid period already booked for later
        // still drops the workspace to the floor plan in between, and "upgrade to keep access" would read as
        // though the purchase had not registered.
        mailer.remindAdmins(message(true, "2026-10-01"));

        assertThat(captureMail().getTemplateCode())
                .as("gap is checked before trial, deliberately")
                .isEqualTo(SubscriptionExpiryReminderMailer.TEMPLATE_GAP_REMINDER);
    }

    @Test
    @DisplayName("a blank resume date counts as no successor, not as a gap")
    void blankNextStart_treatedAsNoSuccessor() {
        // The date crosses MQ as a string, so "absent" can arrive as "" as easily as null. Reading empty as a
        // gap would render the notice with a missing date — "resuming " with nothing after it.
        mailer.remindAdmins(message(false, "  "));

        assertThat(captureMail().getTemplateCode())
                .isEqualTo(SubscriptionExpiryReminderMailer.TEMPLATE_EXPIRY_REMINDER);
    }

    // ─── what the template gets, and who it goes to ───

    @Test
    @DisplayName("the resume date is passed through for the notice to name it")
    void gapNoticeCarriesTheResumeDate() {
        mailer.remindAdmins(message(false, "2026-10-01"));

        assertThat(captureMail().getTemplateVariables())
                .containsEntry("nextStartDate", "2026-10-01")
                .containsEntry("tenantName", "Acme Corp")
                .containsEntry("expiryDate", "2026-09-01");
    }

    @Test
    @DisplayName("a null resume date still binds the variable, as empty rather than missing")
    void plainReminderBindsResumeDateAsEmpty() {
        // The templates are shared platform rows; an unbound variable renders as the raw placeholder.
        mailer.remindAdmins(message(false, null));

        assertThat(captureMail().getTemplateVariables()).containsEntry("nextStartDate", "");
    }

    @Test
    @DisplayName("one mail per admin — a shared recipient list would leak addresses")
    void oneMailPerAdmin() {
        // Every recipient of one message sees the others in its To: header. Two tenant admins of the same
        // customer are ordinary; two customers' addresses in one To: header is a data leak.
        when(accountService.getByIds(anyList()))
                .thenReturn(List.of(account("one@acme.test"), account("two@acme.test")));

        mailer.remindAdmins(message(false, null));

        ArgumentCaptor<SendMailDTO> captor = ArgumentCaptor.forClass(SendMailDTO.class);
        verify(messageService, times(2)).sendMail(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(mail -> assertThat(mail.getTo()).hasSize(1));
        assertThat(captor.getAllValues()).flatExtracting(SendMailDTO::getTo)
                .containsExactly("one@acme.test", "two@acme.test");
        // The platform's wording, which a tenant must not re-word; the record stays in the tenant's books.
        assertThat(captor.getAllValues()).extracting(SendMailDTO::getScope).containsOnly(MessageScope.PLATFORM);
    }

    @Test
    @DisplayName("one admin's address that cannot be accepted does not cost the others their reminder")
    void aRejectedAddress_doesNotStopTheOthers() {
        when(accountService.getByIds(anyList()))
                .thenReturn(List.of(account("not an address"), account("two@acme.test")));
        when(messageService.sendMail(any()))
                .thenThrow(new BusinessException("Mail send rejected: invalid to address"))
                .thenReturn(1L);

        mailer.remindAdmins(message(false, null));

        verify(messageService, times(2)).sendMail(any());
    }

    @Test
    @DisplayName("admins with no email address are skipped rather than mailed blank")
    void adminsWithoutEmail_noMail() {
        when(accountService.getByIds(anyList()))
                .thenReturn(List.of(account(null), account("  ")));

        mailer.remindAdmins(message(false, null));

        verify(messageService, never()).sendMail(any());
    }

    private SendMailDTO captureMail() {
        ArgumentCaptor<SendMailDTO> captor = ArgumentCaptor.forClass(SendMailDTO.class);
        verify(messageService).sendMail(captor.capture());
        return captor.getValue();
    }

    @Test
    void downgrade_picksTheDowngradeTemplate() {
        mailer.remindAdmins(message(false, null, "plan.free"));
        SendMailDTO mail = captureMail();

        assertThat(mail.getTemplateCode())
                .isEqualTo(SubscriptionExpiryReminderMailer.TEMPLATE_DOWNGRADE_REMINDER);
        assertThat(mail.getTemplateVariables()).containsEntry("successorPlanId", "plan.free");
    }

    @Test
    void downgrade_outranksTheGapWording() {
        // Both signals present: a later period exists AND a lower tier already covers the day after. The
        // tenant is not cut off for a single day, so the gap wording — which names an uncovered stretch —
        // would describe something that does not happen.
        mailer.remindAdmins(message(false, "2026-12-01", "plan.free"));
        SendMailDTO mail = captureMail();

        assertThat(mail.getTemplateCode())
                .isEqualTo(SubscriptionExpiryReminderMailer.TEMPLATE_DOWNGRADE_REMINDER);
    }

    @Test
    void downgrade_outranksTheTrialWording() {
        // A trial that lapses onto the free plan has not "run out" — upgrade-or-lose-access is untrue.
        mailer.remindAdmins(message(true, null, "plan.free"));
        SendMailDTO mail = captureMail();

        assertThat(mail.getTemplateCode())
                .isEqualTo(SubscriptionExpiryReminderMailer.TEMPLATE_DOWNGRADE_REMINDER);
    }

    @Test
    void blankSuccessor_isTreatedAsNoSuccessor() {
        mailer.remindAdmins(message(false, null, "  "));
        SendMailDTO mail = captureMail();

        assertThat(mail.getTemplateCode())
                .isEqualTo(SubscriptionExpiryReminderMailer.TEMPLATE_EXPIRY_REMINDER);
    }

    private static SubscriptionExpiryReminderMessage message(boolean trial, String nextStartDate) {
        return message(trial, nextStartDate, null);
    }

    /** {@code successorPlanId} non-null = a lower-tier period covers the day after, i.e. a downgrade. */
    private static SubscriptionExpiryReminderMessage message(boolean trial, String nextStartDate,
                                                             String successorPlanId) {
        return new SubscriptionExpiryReminderMessage(TENANT, "Acme Corp", "plan.pro", "2026-09-01", 7,
                trial, nextStartDate, successorPlanId);
    }

    private static UserAccount account(String email) {
        UserAccount account = new UserAccount();
        account.setEmail(email);
        return account;
    }
}
