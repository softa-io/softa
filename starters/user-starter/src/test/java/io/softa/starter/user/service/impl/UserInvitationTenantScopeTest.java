package io.softa.starter.user.service.impl;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.message.MessageScope;
import io.softa.framework.orm.domain.Filters;
import io.softa.starter.message.mail.dto.SendMailDTO;
import io.softa.starter.message.service.MessageService;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.entity.UserIdentity;
import io.softa.starter.user.entity.UserInvitation;
import io.softa.starter.user.enums.AccountStatus;
import io.softa.starter.user.service.UserAccountService;
import io.softa.starter.user.service.UserIdentityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static io.softa.starter.user.TestSystemConfigs.siteAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which tenant the set-password mail is rendered under.
 *
 * <p>The MQ hop drops the thread context, so whatever this side publishes IS the tier the consumer
 * renders from — and since template resolution no longer falls back across tiers, publishing the
 * wrong one is not a degraded render but a different company's wording and mail server, or an
 * outright "No mail template found".
 *
 * <p>The two entry points reach the tier differently and both have to hold: {@code invite} is authed
 * and renders under the invitee's tenant, while {@code forgotPassword} is anonymous with no context
 * at all and deliberately renders platform-tier.
 */
class UserInvitationTenantScopeTest {

    private static final Long OPERATOR_TENANT = 100L;
    private static final Long ACCOUNT_TENANT = 200L;
    private static final Long USER_ID = 9001L;

    private UserAccountService accountService;
    private UserIdentityService identityService;
    private MessageService messageService;
    private UserInvitationServiceImpl service;
    /** Null when the insert ran cross-tenant — i.e. when the ORM skipped stamping entirely. */
    private AtomicReference<Long> tenantSeenByRowInsert;
    /**
     * The tenant the mail was sent in, which its send record is filed under; null when sent from a
     * context with no tenant — message-starter files a platform-scope send under the platform tier.
     */
    private AtomicReference<Long> tenantSeenByMail;

    @BeforeEach
    void setUp() {
        accountService = mock(UserAccountService.class);
        identityService = mock(UserIdentityService.class);
        messageService = mock(MessageService.class);
        tenantSeenByMail = new AtomicReference<>();
        when(messageService.sendMail(any())).thenAnswer(inv -> {
            tenantSeenByMail.set(ContextHolder.getContext().getTenantId());
            return 1L;
        });

        // tenantInfoService is null: it only supplies the company NAME shown on the /join screens,
        // and this test is about which tenant tier the mail is rendered under.
        service = spy(new UserInvitationServiceImpl(accountService, identityService, messageService,
                null, mock(JoinProofGuard.class), mock(VerificationCodeGuard.class), siteAt("https://app.example.test/")));
        // Stub the inherited ORM surface: this test is about which tier reaches the mail, not about
        // how the invitation row is stored.
        doReturn(List.<UserInvitation>of()).when(service).searchList(any(Filters.class));
        tenantSeenByRowInsert = new AtomicReference<>();
        doAnswer(inv -> {
            // The ORM stamps tenant_id from the ambient context at insert time, so what that context says
            // HERE is the tenant the row lands in. Asserting on the entity would prove nothing: tenant_id
            // is readonly and never set on it. Cross-tenant means no stamp at all, recorded as null.
            Context ctx = ContextHolder.getContext();
            tenantSeenByRowInsert.set(ctx.isCrossTenant() ? null : ctx.getTenantId());
            return 1L;
        }).when(service).createOne(any(UserInvitation.class));

        UserIdentity identity = new UserIdentity();
        identity.setProfileId(7L);
        identity.setLoginEmail("invitee@example.test");
        identity.setPassword("already-set");
        when(identityService.requireIdentity(any(UserAccount.class))).thenReturn(identity);
        // forgotPassword resolves the PERSON by login identifier and issues against an ACTIVE row of
        // theirs (ForgotPasswordScopeTest owns that gate); here it only has to reach issue().
        when(identityService.findByLoginIdentifier("invitee@example.test")).thenReturn(Optional.of(identity));
        when(accountService.listMembershipsOf(7L)).thenAnswer(inv -> List.of(activeAccount()));
    }

    private UserAccount account() {
        UserAccount account = new UserAccount();
        account.setId(USER_ID);
        account.setEmail("invitee@example.test");
        account.setProfileId(7L);
        account.setTenantId(ACCOUNT_TENANT);
        return account;
    }

    private UserAccount activeAccount() {
        UserAccount account = account();
        account.setStatus(AccountStatus.ACTIVE);
        return account;
    }

    private SendMailDTO captureMail() {
        ArgumentCaptor<SendMailDTO> captor = ArgumentCaptor.forClass(SendMailDTO.class);
        verify(messageService).sendMail(captor.capture());
        return captor.getValue();
    }

    /** Run as an operator sitting in a tenant of their own, the way the authed invite path does. */
    private void inviteAsOperatorFrom(Long operatorTenant) {
        Context ctx = new Context();
        ctx.setTenantId(operatorTenant);
        ContextHolder.runWith(ctx, () -> service.invite(USER_ID, null));
    }

    @Test
    void inviteRendersUnderTheInviteesTenantNotTheOperators() {
        // The case that separates the two: an operator who can see accounts across tenants clicks
        // Invite. Sourcing the tier from the ambient context puts THEIR company's template and SMTP
        // in front of another company's member — and with no cross-tier fallback left, a template
        // the operator's tenant happens not to have is an outright failure instead of a wrong logo.
        when(accountService.getById(USER_ID)).thenReturn(Optional.of(account()));

        inviteAsOperatorFrom(OPERATOR_TENANT);

        SendMailDTO mail = captureMail();
        assertThat(tenantSeenByMail.get()).isEqualTo(ACCOUNT_TENANT);
        assertThat(tenantSeenByMail.get()).isNotEqualTo(OPERATOR_TENANT);
        assertThat(mail.getScope()).isEqualTo(MessageScope.TENANT);
    }

    @Test
    void provisioningPathIsUnaffected() {
        // Provisioning invites inside inTenantContext(newTenantId), where the account was just
        // created, so ambient and account agree. Pinned so the fix above cannot regress the path
        // that already worked.
        when(accountService.getById(USER_ID)).thenReturn(Optional.of(account()));

        inviteAsOperatorFrom(ACCOUNT_TENANT);

        captureMail();
        assertThat(tenantSeenByMail.get()).isEqualTo(ACCOUNT_TENANT);
    }

    @Test
    void forgotPasswordStaysPlatformTierWhileFiledUnderThePersonsOnlyCompany() {
        // /login/forgetPassword is in public-uri-patterns: no session, no tenant context. It stays
        // platform-tier deliberately — template resolution has no fallback across tiers, so reaching
        // for the account's tenant would turn a missing or disabled tenant copy into "No mail
        // template found" on a path with no operator around to see it. Whom it is filed under is a
        // separate question: someone in one company is that company's send, counted against its quota.
        when(accountService.soleJoinedTenantOf(7L)).thenReturn(Optional.of(ACCOUNT_TENANT));

        service.forgotPassword("invitee@example.test");

        SendMailDTO mail = captureMail();
        assertThat(mail.getScope()).isEqualTo(MessageScope.PLATFORM);
        assertThat(tenantSeenByMail.get()).isEqualTo(ACCOUNT_TENANT);
    }

    @Test
    void forgotPasswordOfSomeoneInSeveralCompaniesIsThePlatformsSend() {
        // The reset sets the person's one password: with two companies it is neither one's, so it is
        // sent with no tenant context and message-starter files it under the platform.
        when(accountService.soleJoinedTenantOf(7L)).thenReturn(Optional.empty());

        service.forgotPassword("invitee@example.test");

        assertThat(captureMail().getScope()).isEqualTo(MessageScope.PLATFORM);
        assertThat(tenantSeenByMail.get()).isNull();
    }

    @Test
    void aResetIsThePlatformsMailEvenInsideATenant() {
        // A password reset is an account credential: the platform's template through the platform's
        // servers whatever the context, so no company can rewrite or swallow it.
        Context ctx = new Context();
        ctx.setTenantId(OPERATOR_TENANT);
        ContextHolder.runWith(ctx, () -> service.forgotPassword("invitee@example.test"));

        assertThat(captureMail().getScope()).isEqualTo(MessageScope.PLATFORM);
    }

    @Test
    void theRowLandsInTheInviteesTenantEvenWhenTheCallerIsCrossTenant() {
        // UserAccountController.invite wraps the call in a cross-tenant window so a platform super-admin
        // can reach a roster spanning tenants. That window also switches off tenant stamping, so the
        // invitation row used to land unstamped — invisible on the User Invitations page of the very
        // tenant whose member it is about, which is the one case where someone acts on their behalf.
        when(accountService.getById(USER_ID)).thenReturn(Optional.of(account()));

        Context crossTenant = new Context();
        crossTenant.setTenantId(OPERATOR_TENANT);
        crossTenant.setCrossTenant(true);
        ContextHolder.runWith(crossTenant, () -> service.invite(USER_ID, null));

        assertThat(tenantSeenByRowInsert.get())
                .as("row must be filed under the invitee's tenant, not left unstamped")
                .isEqualTo(ACCOUNT_TENANT);
    }

    @Test
    void theRowIsOwnedEvenWhenTheMailIsRenderedPlatformTier() {
        // Ownership and render tier answer different questions, and the public reset path is where
        // they part: no context to trust, so the mail renders platform-tier — but the invitation is
        // still a record of THIS person's account at THEIR company, and belongs to that tenant. An
        // earlier revision asserted the two were always equal, which this path disproves.
        service.forgotPassword("invitee@example.test");

        assertThat(tenantSeenByRowInsert.get())
                .as("the row belongs to the account's tenant regardless of which tier renders the mail")
                .isEqualTo(ACCOUNT_TENANT);
        assertThat(captureMail().getScope()).isEqualTo(MessageScope.PLATFORM);
        assertThat(tenantSeenByMail.get()).isNull();
    }

    @Test
    void theLinkIsStillTheSetPasswordUrlWithASingleSlash() {
        // The trailing slash on the configured base url is trimmed; a doubled slash would 404 on
        // some proxies and is invisible in a passing "mail was published" assertion.
        service.forgotPassword("invitee@example.test");

        assertThat((String) captureMail().getTemplateVariables().get("link"))
                .startsWith("https://app.example.test/set-password?token=");
    }
}
