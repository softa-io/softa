package io.softa.starter.user.service.impl;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import io.softa.framework.base.message.MailRequestMessage;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.base.exception.BusinessException;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.entity.UserIdentity;
import io.softa.starter.user.entity.UserInvitation;
import io.softa.starter.user.enums.AccountStatus;
import io.softa.starter.user.service.UserAccountService;
import io.softa.starter.user.service.UserIdentityService;

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
 * Who a self-service password reset may be issued for.
 *
 * <p>The trace this closes: Ada left, kept ada@personal.com as her login, never set a password, and
 * was re-hired. Her revived row sits PENDING with ada@acme.com — a mailbox the company holds — in
 * its work-email column. Before this, forgotPassword matched that column, mailed /set-password to
 * that mailbox, and acceptToken set Ada's GLOBAL password from it: the company became Ada at every
 * company she belongs to. The mailbox proves the company, not the person, so the reset resolves
 * the PERSON by login identifier and issues only for one who already has a password and an ACTIVE
 * row — while answering every identifier the same way, so nothing here says who is registered.
 *
 * <p>The second trace: resolving the person correctly is not enough if the link is then mailed
 * to an ACTIVE row's WORK address. Ada's login is ada@personal.com, with a password; she is ACTIVE
 * at a company whose mailbox for her the company holds. HR types her personal login — it is on
 * file — and the link lands in the company mailbox. The address the link goes to is the proof:
 * a work mailbox proves the company, the login identifier proves the person. So the link goes to
 * the identifier that resolved the person, and the ACTIVE row is bookkeeping only.
 */
class ForgotPasswordScopeTest {

    private static final Long ADA = 7L;
    private static final String WORK_EMAIL = "ada@acme.com";
    private static final String PERSONAL_EMAIL = "ada@personal.com";

    private final UserAccountService accountService = mock(UserAccountService.class);
    private final UserIdentityService identityService = mock(UserIdentityService.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final UserInvitationServiceImpl service = spy(new UserInvitationServiceImpl(
            accountService, identityService, eventPublisher, null, mock(JoinProofGuard.class),
            "https://app.example.test"));

    ForgotPasswordScopeTest() {
        // The inherited ORM surface: this test is about whether a token is issued, not how it is stored.
        doReturn(List.<UserInvitation>of()).when(service).searchList(any(Filters.class));
        doReturn(1L).when(service).createOne(any(UserInvitation.class));
        // The work column still matches the revived row — that is exactly the lookup that must no
        // longer decide anything.
        when(accountService.getUserByEmail(anyString())).thenAnswer(inv -> Optional.empty());
    }

    private UserIdentity ada(String loginEmail, String password) {
        UserIdentity identity = new UserIdentity();
        identity.setId(11L);
        identity.setProfileId(ADA);
        identity.setLoginEmail(loginEmail);
        identity.setPassword(password);
        if (loginEmail != null) {
            when(identityService.findByLoginIdentifier(loginEmail)).thenReturn(Optional.of(identity));
        }
        return identity;
    }

    private UserAccount row(Long id, Long tenantId, AccountStatus status, String workEmail) {
        UserAccount account = new UserAccount();
        account.setId(id);
        account.setTenantId(tenantId);
        account.setProfileId(ADA);
        account.setStatus(status);
        account.setEmail(workEmail);
        return account;
    }

    private void nothingIssued() {
        verify(service, never()).createOne(any(UserInvitation.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    private UserInvitation issuedInvitation() {
        ArgumentCaptor<UserInvitation> captor = ArgumentCaptor.forClass(UserInvitation.class);
        verify(service).createOne(captor.capture());
        return captor.getValue();
    }

    @Test
    void theRevivedRowsWorkMailbox_issuesNothing() {
        // Ada's PENDING revived row carries the work address; her login is personal and she has no
        // password. The company typing its own mailbox must get exactly what an unknown address gets.
        UserAccount revived = row(100L, 1L, AccountStatus.PENDING, WORK_EMAIL);
        when(accountService.getUserByEmail(WORK_EMAIL)).thenReturn(Optional.of(revived));
        ada(PERSONAL_EMAIL, null);
        when(identityService.findByLoginIdentifier(WORK_EMAIL)).thenReturn(Optional.empty());
        when(accountService.listMembershipsOf(ADA)).thenReturn(List.of(revived));

        assertThatThrownBy(() -> service.forgotPassword(WORK_EMAIL))
                .isInstanceOf(BusinessException.class);

        // Load-bearing: no token row, no mail — the work column is never consulted for a reset.
        // The refusal is the same one an unknown address gets, which is the point: a work mailbox
        // is not a login identifier, and nothing about the answer says the row exists.
        nothingIssued();
    }

    private MailRequestMessage sentMail() {
        ArgumentCaptor<Object> mail = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(mail.capture());
        return (MailRequestMessage) mail.getValue();
    }

    @Test
    void anActivePersonWithAPassword_getsATokenAgainstAnActiveRow_mailedToHerLogin() {
        // Ada's login is personal; her only ACTIVE row carries a mailbox the OTHER company holds.
        // HR at that company types the personal login it has on file.
        ada(PERSONAL_EMAIL, "hash");
        UserAccount pendingElsewhere = row(100L, 1L, AccountStatus.PENDING, WORK_EMAIL);
        UserAccount active = row(200L, 2L, AccountStatus.ACTIVE, "ada@other.com");
        when(accountService.listMembershipsOf(ADA)).thenReturn(List.of(pendingElsewhere, active));

        service.forgotPassword(PERSONAL_EMAIL);

        // The row the token names is what acceptToken reads back; it must be one already confirmed.
        UserInvitation invitation = issuedInvitation();
        assertThat(invitation.getUserId()).isEqualTo(200L);
        // Load-bearing: the link goes to the identifier that proved the person — never to the
        // ACTIVE row's work mailbox, which would hand the company the person's global password.
        assertThat(sentMail().to()).containsExactly(PERSONAL_EMAIL).doesNotContain("ada@other.com");
        assertThat(invitation.getEmail()).isEqualTo(PERSONAL_EMAIL);
        assertThat(invitation.getMobile()).isNull();
    }

    @Test
    void theRowsWorkMobile_isNeverAChannelForAReset() {
        // The pinned address replaces the whole fan-out, not just the email half of it.
        ada(PERSONAL_EMAIL, "hash");
        UserAccount active = row(200L, 2L, AccountStatus.ACTIVE, "ada@other.com");
        active.setMobile("+6591234567");
        when(accountService.listMembershipsOf(ADA)).thenReturn(List.of(active));

        service.forgotPassword(PERSONAL_EMAIL);

        assertThat(sentMail().to()).containsExactly(PERSONAL_EMAIL);
        assertThat(issuedInvitation().getMobile()).isNull();
    }

    @Test
    void aLoginThatIsAlsoTheRowsWorkEmail_isMailedThere_andFiledUnderThatRow() {
        // Two ACTIVE tenants, the person's login IS one company's address: the link goes to that
        // address (it is hers to sign in with), filed under that company's row rather than
        // whichever row the query happened to return first.
        ada(WORK_EMAIL, "hash");
        UserAccount other = row(200L, 2L, AccountStatus.ACTIVE, "ada@other.com");
        UserAccount acme = row(300L, 3L, AccountStatus.ACTIVE, "Ada@Acme.com");
        when(accountService.listMembershipsOf(ADA)).thenReturn(List.of(other, acme));

        service.forgotPassword(WORK_EMAIL);

        assertThat(issuedInvitation().getUserId()).isEqualTo(300L);
        assertThat(issuedInvitation().getEmail()).isEqualTo(WORK_EMAIL);
        assertThat(sentMail().to()).containsExactly(WORK_EMAIL);
    }

    @Test
    void anActiveRowWithNoWorkEmail_stillGetsTheLink_atTheLogin() {
        // Delivery never depended on the row's mailbox, so a row without one is as good as any.
        ada(PERSONAL_EMAIL, "hash");
        when(accountService.listMembershipsOf(ADA)).thenReturn(List.of(
                row(200L, 2L, AccountStatus.ACTIVE, null)));

        service.forgotPassword(PERSONAL_EMAIL);

        assertThat(issuedInvitation().getUserId()).isEqualTo(200L);
        assertThat(sentMail().to()).containsExactly(PERSONAL_EMAIL);
    }

    @Test
    void aMobileLogin_getsNothing_thereIsNoMailboxToProve() {
        // The identifier resolves the person, but a reset link is mailed and the only address that
        // proves the person is their login email; anything else would be a work mailbox again.
        UserIdentity identity = ada(PERSONAL_EMAIL, "hash");
        identity.setLoginMobile("+6591234567");
        when(identityService.findByLoginIdentifier("+6591234567")).thenReturn(Optional.of(identity));
        when(accountService.listMembershipsOf(ADA)).thenReturn(List.of(
                row(200L, 2L, AccountStatus.ACTIVE, "ada@other.com")));

        service.forgotPassword("+6591234567");

        nothingIssued();
    }

    @Test
    void aPasswordHolderWithOnlyInvitedRows_getsNothing() {
        // The identifier is theirs and a password exists, but no membership has been confirmed:
        // /join's confirm step still guards every row, and a reset link would route around it.
        ada(PERSONAL_EMAIL, "hash");
        when(accountService.listMembershipsOf(ADA)).thenReturn(List.of(
                row(100L, 1L, AccountStatus.INVITED, WORK_EMAIL),
                row(200L, 2L, AccountStatus.INVITED, "ada@other.com")));

        service.forgotPassword(PERSONAL_EMAIL);

        nothingIssued();
    }

    @Test
    void anIdentityWithNoPassword_getsNothing_evenWithAnActiveRow() {
        // A reset presupposes a credential to replace. The first password is set in-session or on
        // /join behind a code — never from a mailbox alone.
        ada(PERSONAL_EMAIL, null);
        when(accountService.listMembershipsOf(ADA)).thenReturn(List.of(
                row(200L, 2L, AccountStatus.ACTIVE, "ada@other.com")));

        service.forgotPassword(PERSONAL_EMAIL);

        nothingIssued();
    }

    @Test
    void anUnknownIdentifier_isRefusedByName() {
        // Existence is the one fact this path now answers, matching the code-send entry points:
        // starting the same errand from the same screen must not say "exists" by omission, and
        // whoever mistyped is left waiting for a mail that was never going to come.
        when(identityService.findByLoginIdentifier(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.forgotPassword("nobody@example.test"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("This email is not linked to any account. Please contact your administrator.");

        nothingIssued();
        verify(accountService, never()).listMembershipsOf(any());
    }

    @Test
    void theIdentifierIsLookedUpTheWayLoginSpellsIt() {
        // Trimmed and lowercased before the lookup, as login does, so "Ada@Personal.com " resets
        // the same person who signs in as ada@personal.com.
        ada(PERSONAL_EMAIL, "hash");
        when(accountService.listMembershipsOf(ADA)).thenReturn(List.of(
                row(200L, 2L, AccountStatus.ACTIVE, "ada@other.com")));

        service.forgotPassword("  Ada@Personal.com ");

        verify(identityService).findByLoginIdentifier(PERSONAL_EMAIL);
        assertThat(issuedInvitation().getUserId()).isEqualTo(200L);
        // Recorded and mailed in canonical form — acceptToken compares it against the login email.
        assertThat(issuedInvitation().getEmail()).isEqualTo(PERSONAL_EMAIL);
        assertThat(sentMail().to()).containsExactly(PERSONAL_EMAIL);
    }
}
