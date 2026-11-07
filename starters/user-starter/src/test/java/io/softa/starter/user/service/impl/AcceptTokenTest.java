package io.softa.starter.user.service.impl;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.domain.Filters;
import io.softa.starter.message.service.MessageService;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.entity.UserIdentity;
import io.softa.starter.user.entity.UserInvitation;
import io.softa.starter.user.enums.AccountStatus;
import io.softa.starter.user.enums.InvitationPurpose;
import io.softa.starter.user.enums.InvitationStatus;
import io.softa.starter.user.service.UserAccountService;
import io.softa.starter.user.service.UserIdentityService;

import static io.softa.starter.user.TestSystemConfigs.siteAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The emailed-link token REPLACES a password and does nothing else.
 *
 * <p>Two properties. Activation belongs to /join's confirm step, where the person agrees to join
 * the company — if accepting a token also activated a membership, a reset link would become a way
 * to skip that step. And the token only ever replaces a credential that exists, on a membership
 * that is already ACTIVE: the link lands in a work mailbox the company holds, so a token pointing
 * at a PENDING row or at an identity with no password would let the company set the person's
 * GLOBAL first password — the takeover forgotPassword refuses to issue for, re-proved here because
 * the link is anonymous and lives for days. And the address the link was mailed to must be the
 * person's own login email: the address is the proof of who redeems it, a work mailbox proves the
 * company, so a token addressed anywhere else is refused.
 */
class AcceptTokenTest {

    private static final Long ACCOUNT = 100L;
    private static final Long PERSON = 7L;
    private static final String LOGIN_EMAIL = "ada@personal.com";

    private final UserAccountService accountService = mock(UserAccountService.class);
    private final UserIdentityService identityService = mock(UserIdentityService.class);
    private final UserInvitationServiceImpl invitationService = spy(new UserInvitationServiceImpl(
            accountService, identityService, mock(MessageService.class), null,
            mock(JoinProofGuard.class), mock(VerificationCodeGuard.class), siteAt("http://localhost:3000")));
    private final io.softa.starter.user.service.UserSecurityLogService invitationSecurityLog =
            InvitationServiceTestSupport.quiet(invitationService);

    private UserAccount tokenFor(AccountStatus status, String password) {
        return tokenFor(status, password, LOGIN_EMAIL);
    }

    private UserAccount tokenFor(AccountStatus status, String password, String sentTo) {
        UserAccount account = new UserAccount();
        account.setId(ACCOUNT);
        account.setProfileId(PERSON);
        account.setStatus(status);
        account.setEmail("ada@acme.com");
        when(accountService.getById(ACCOUNT)).thenReturn(Optional.of(account));
        UserIdentity identity = new UserIdentity();
        identity.setId(11L);
        identity.setProfileId(PERSON);
        identity.setLoginEmail(LOGIN_EMAIL);
        identity.setPassword(password);
        when(identityService.findByProfile(PERSON)).thenReturn(Optional.of(identity));
        // The address signs this person in (case aside); any other signs in nobody.
        when(identityService.findByLoginIdentifier(org.mockito.ArgumentMatchers.anyString())).thenAnswer(inv ->
                LOGIN_EMAIL.equalsIgnoreCase(String.valueOf(inv.<String>getArgument(0)).trim())
                        ? Optional.of(identity) : Optional.empty());
        UserInvitation invitation = new UserInvitation();
        invitation.setId(1L);
        invitation.setUserId(ACCOUNT);
        invitation.setEmail(sentTo);
        invitation.setPurpose(InvitationPurpose.PASSWORD_RESET);
        invitation.setStatus(InvitationStatus.PENDING);
        invitation.setExpiresAt(LocalDateTime.now().plusHours(1));
        doReturn(Optional.of(invitation)).when(invitationService).searchOne(any(Filters.class));
        doReturn(true).when(invitationService).updateOne(any(UserInvitation.class));
        return account;
    }

    @Test
    void acceptingATokenLeavesTheAccountStatusAlone() {
        UserAccount account = tokenFor(AccountStatus.ACTIVE, "hash");
        LocalDateTime activated = LocalDateTime.now().minusDays(30);
        account.setActivationTime(activated);

        invitationService.acceptToken("raw-token", "Str0ng!Passw0rd");

        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.getActivationTime()).isEqualTo(activated);
        verify(accountService, never()).updateOne(any(UserAccount.class));
    }

    @Test
    void aResetByLink_endsEverySession_dropsEveryDevice_andIsLogged() {
        // As a reset by code does: whoever knew the old password may be signed in somewhere.
        tokenFor(AccountStatus.ACTIVE, "hash");
        io.softa.starter.user.service.UserSessionService sessions = mock(io.softa.starter.user.service.UserSessionService.class);
        io.softa.starter.user.spi.PushDeviceRegistry devices = mock(io.softa.starter.user.spi.PushDeviceRegistry.class);
        org.springframework.test.util.ReflectionTestUtils.setField(invitationService, "sessionService", sessions);
        org.springframework.test.util.ReflectionTestUtils.setField(invitationService, "pushDeviceRegistry", devices);

        invitationService.acceptToken("raw-token", "Str0ng!Passw0rd");

        verify(sessions).revokePerson(PERSON, null, io.softa.framework.web.session.SessionRevokeReason.PASSWORD_RESET);
        verify(devices).unregisterPerson(PERSON);
        verify(invitationSecurityLog).entry(org.mockito.ArgumentMatchers.eq(io.softa.starter.user.enums.SecurityAction.PASSWORD_RESET),
                any(), any(), org.mockito.ArgumentMatchers.eq(PERSON));
    }

    @Test
    void acceptingATokenSetsThePasswordAndClosesTheInvitation() {
        tokenFor(AccountStatus.ACTIVE, "hash");

        invitationService.acceptToken("raw-token", "Str0ng!Passw0rd");

        verify(identityService).setPassword(any(UserAccount.class), org.mockito.ArgumentMatchers.eq("Str0ng!Passw0rd"));
        verify(invitationService).updateOne(org.mockito.ArgumentMatchers
                .argThat((UserInvitation i) -> i.getStatus() == InvitationStatus.ACCEPTED && i.getAcceptedAt() != null));
    }

    @Test
    void aTokenNamingAPendingRow_isRefused_andNoPasswordIsWritten() {
        // A re-hired leaver's revived row: bound to her, PENDING, work email = the company's mailbox.
        // The token was minted before the issue-side gate existed (or by anything else that names
        // a row nobody has confirmed). Load-bearing: setPassword never runs.
        tokenFor(AccountStatus.PENDING, "hash");

        assertThatThrownBy(() -> invitationService.acceptToken("raw-token", "Str0ng!Passw0rd"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("This link is no longer valid. Please contact your administrator.");

        verify(identityService, never()).setPassword(any(UserAccount.class), anyString());
        verify(identityService, never()).setPassword(any(Long.class), anyString());
        verify(invitationService, never()).updateOne(any(UserInvitation.class));
    }

    @Test
    void aTokenMailedToTheRowsWorkAddress_isRefused_andNoPasswordIsWritten() {
        // ACTIVE row, password set — and the link went to the company's mailbox for her, not to
        // her login. Whoever holds that mailbox holds this token; it was issued before delivery
        // was pinned to the login identifier (or by anything else that addresses a work mailbox).
        // Load-bearing: setPassword never runs.
        tokenFor(AccountStatus.ACTIVE, "hash", "ada@acme.com");

        assertThatThrownBy(() -> invitationService.acceptToken("raw-token", "Str0ng!Passw0rd"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("This link is no longer valid. Please contact your administrator.");

        verify(identityService, never()).setPassword(any(UserAccount.class), anyString());
        verify(invitationService, never()).updateOne(any(UserInvitation.class));
    }

    @Test
    void aTokenWithNoAddressRecorded_isRefused() {
        // Rows from before the address was recorded carry nothing to compare — a blank must not
        // pass as "matches nothing in particular".
        tokenFor(AccountStatus.ACTIVE, "hash", null);

        assertThatThrownBy(() -> invitationService.acceptToken("raw-token", "Str0ng!Passw0rd"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("This link is no longer valid. Please contact your administrator.");

        verify(identityService, never()).setPassword(any(UserAccount.class), anyString());
    }

    @Test
    void theAddressIsComparedInCanonicalForm() {
        // Spelling differences between what was recorded and what the identity holds are not a
        // reason to strand the person who did receive the link.
        tokenFor(AccountStatus.ACTIVE, "hash", "  Ada@Personal.com ");

        invitationService.acceptToken("raw-token", "Str0ng!Passw0rd");

        verify(identityService).setPassword(any(UserAccount.class), org.mockito.ArgumentMatchers.eq("Str0ng!Passw0rd"));
    }

    @Test
    void aTokenForAnIdentityWithNoPassword_isRefused() {
        // Nothing to RESET: a first password is set in-session or through /join with a code, never
        // from a mailbox alone.
        tokenFor(AccountStatus.ACTIVE, null);

        assertThatThrownBy(() -> invitationService.acceptToken("raw-token", "Str0ng!Passw0rd"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("This link is no longer valid. Please contact your administrator.");

        verify(identityService, never()).setPassword(any(UserAccount.class), anyString());
    }
}
