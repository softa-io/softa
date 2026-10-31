package io.softa.starter.user.service.impl;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.message.MailRequestMessage;
import io.softa.framework.base.message.SmsRequestMessage;
import io.softa.starter.user.dto.WorkContacts;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.entity.UserIdentity;
import io.softa.starter.user.enums.AccountStatus;
import io.softa.starter.user.service.UserIdentityService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Reset User (W9) — the everyday case: the RIGHT person holds this membership and their email or
 * phone simply changed. Three things make it correct rather than a smaller unbind:
 *
 * <ul>
 *   <li>the LOGIN identifier moves with the contact — leaving it behind means the person signs in
 *       with an address this company no longer knows, while the recycled one becomes a route into
 *       their account for whoever receives it next;</li>
 *   <li>only the value this company ISSUED is rewritten — a personal login email is not ours;</li>
 *   <li>the OLD address is notified, so a reset the person did not ask for reaches somewhere they
 *       can still read.</li>
 * </ul>
 */
class ResetWorkContactsTest {

    private static final Long ACCOUNT = 100L;
    private static final Long PROFILE = 7L;

    private final UserIdentityService identityService = mock(UserIdentityService.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final UserAccountServiceImpl accountService = spy(new UserAccountServiceImpl());
    private final io.softa.starter.user.service.UserSecurityLogService securityLog =
            AccountServiceTestSupport.quiet(accountService);

    ResetWorkContactsTest() {
        ReflectionTestUtils.setField(accountService, "identityService", identityService);
        ReflectionTestUtils.setField(accountService, "eventPublisher", eventPublisher);
    }

    /** What the employee record says the contacts are — the value this operation carries over. */
    private void archive(String email, String mobile) {
        doReturn(new WorkContacts(email, mobile)).when(accountService).archiveWorkContacts(ACCOUNT);
    }

    private UserAccount given(String email, String mobile, Long profileId) {
        UserAccount account = new UserAccount();
        account.setId(ACCOUNT);
        account.setProfileId(profileId);
        account.setStatus(AccountStatus.ACTIVE);
        account.setEmail(email);
        account.setMobile(mobile);
        doReturn(Optional.of(account)).when(accountService).getById(ACCOUNT);
        // The employee record is where the new contacts come from now (S-B / D23); each test sets
        // what it says via archive(...).
        doReturn(Optional.empty()).when(accountService).getUserByEmail(any());
        doReturn(true).when(accountService).updateOne(any(UserAccount.class), any(Boolean.class));
        return account;
    }

    private static UserIdentity identity(String loginEmail, String loginMobile) {
        UserIdentity identity = new UserIdentity();
        identity.setId(11L);
        identity.setProfileId(PROFILE);
        identity.setLoginEmail(loginEmail);
        identity.setLoginMobile(loginMobile);
        identity.setPassword("hash");
        return identity;
    }

    @Test
    void theLoginIdentifierMovesWithTheWorkContact() {
        UserAccount account = given("old@acme.com", null, PROFILE);
        UserIdentity person = identity("old@acme.com", null);
        when(identityService.findByProfile(PROFILE)).thenReturn(Optional.of(person));

        archive("new@acme.com", null);
        accountService.resetWorkContacts(ACCOUNT, "Address changed");

        assertThat(account.getEmail()).isEqualTo("new@acme.com");
        assertThat(person.getLoginEmail()).isEqualTo("new@acme.com");
        // The overload that keeps nulls: clearing one channel means writing a null, and the default
        // one would drop it, leaving the old identifier a live login route.
        verify(identityService).updateOne(person, false);
        // The password is untouched — this is not a re-invitation.
        assertThat(person.getPassword()).isEqualTo("hash");
        assertThat(account.getProfileId()).isEqualTo(PROFILE);
    }

    @Test
    void theLoginIdentifierIsStoredInCanonicalForm_whileTheContactKeepsItsCase() {
        // The identifier is what login looks up, so it is written the way login asks — otherwise the
        // person could sign in only by reproducing HR's capitalisation. The account keeps HR's case
        // (it is displayed) but not HR's whitespace: the contact columns are queried by equality
        // with a trimmed value, and a stored stray space hid the row from the shared-contact guard.
        UserAccount account = given("Old@Acme.com", null, PROFILE);
        UserIdentity person = identity("old@acme.com", null);
        when(identityService.findByProfile(PROFILE)).thenReturn(Optional.of(person));

        archive(" New@Acme.com ", null);
        accountService.resetWorkContacts(ACCOUNT, "Address changed");

        assertThat(account.getEmail()).isEqualTo("New@Acme.com");
        assertThat(person.getLoginEmail()).isEqualTo("new@acme.com");
        verify(identityService).updateOne(person, false);
    }

    @Test
    void aPersonalLoginEmailIsLeftAlone() {
        // The work email and the login email need not be the same value. Only the address this
        // company issued may be rewritten.
        given("old@acme.com", null, PROFILE);
        UserIdentity person = identity("alice.personal@gmail.com", null);
        when(identityService.findByProfile(PROFILE)).thenReturn(Optional.of(person));

        archive("new@acme.com", null);
        accountService.resetWorkContacts(ACCOUNT, "Address changed");

        assertThat(person.getLoginEmail()).isEqualTo("alice.personal@gmail.com");
        verify(identityService, never()).updateOne(any(UserIdentity.class), any(Boolean.class));
    }

    @Test
    void theOldAddressIsNotified_notTheNewOne() {
        // If the reset was not the person's own doing, the message must reach where they can still
        // read it. Telling only the new address informs whoever now holds it.
        given("old@acme.com", null, PROFILE);
        when(identityService.findByProfile(PROFILE)).thenReturn(Optional.of(identity("old@acme.com", null)));

        archive("new@acme.com", null);
        accountService.resetWorkContacts(ACCOUNT, "Address changed");

        var captor = forClass(MailRequestMessage.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().to()).containsExactly("old@acme.com");
    }

    @Test
    void anAccountNobodyHoldsYet_isRefused() {
        // Nothing to reset: the membership has no person, so this is an invitation, not a reset.
        given("old@acme.com", null, null);

        archive("new@acme.com", null);

        assertThatThrownBy(() -> accountService.resetWorkContacts(ACCOUNT, "x"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("invite it instead");
    }

    @Test
    void anEmployeeRecordWithNoContacts_isRefused() {
        // Nothing to reset TO. The message points at the record, because that is where the fix is:
        // the operation cannot invent a contact and no longer accepts one from its caller.
        given("old@acme.com", "+8613800138000", PROFILE);
        archive(null, null);

        assertThatThrownBy(() -> accountService.resetWorkContacts(ACCOUNT, "x"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("record has no work email or work mobile");
    }

    @Test
    void anAddressAnotherAccountHolds_isRefused() {
        given("old@acme.com", null, PROFILE);
        UserAccount other = new UserAccount();
        other.setId(999L);
        // Scoped to THIS company, not globally: the same work email in ANOTHER company is one
        // person working at two, which is now allowed to exist and must stay editable.
        doReturn(Optional.of(other)).when(accountService)
                .findContactHolderInTenant("taken@acme.com", ACCOUNT);

        archive("taken@acme.com", null);

        assertThatThrownBy(() -> accountService.resetWorkContacts(ACCOUNT, "x"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already belongs to another account");
        verify(identityService, never()).updateOne(any(UserIdentity.class), any(Boolean.class));
    }

    @Test
    void aMobileAnotherAccountHolds_isRefused_notJustTheEmail() {
        // The mobile is a login identifier as much as the email is. Checking only the email let a
        // reset move a number onto this account while a colleague's row still held it, so a code
        // sent there named two people.
        given("old@acme.com", "+8613800138000", PROFILE);
        UserAccount other = new UserAccount();
        other.setId(999L);
        doReturn(Optional.of(other)).when(accountService)
                .findContactHolderInTenant("+8613899999999", ACCOUNT);

        archive("old@acme.com", "+8613899999999");

        assertThatThrownBy(() -> accountService.resetWorkContacts(ACCOUNT, "x"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already belongs to another account");
        verify(identityService, never()).updateOne(any(UserIdentity.class), any(Boolean.class));
        verify(accountService, never()).updateOne(any(UserAccount.class), any(Boolean.class));
    }

    @Test
    void aMobileOnlyAccount_isNotifiedBySms_notLeftUninformed() {
        // The person reachable only by work mobile is exactly the one an email-only notice would
        // leave in the dark. W6/W9 says notify the OLD contact; the old contact here is a number.
        given(null, "+8613800138000", PROFILE);
        when(identityService.findByProfile(PROFILE))
                .thenReturn(Optional.of(identity(null, "+8613800138000")));

        archive(null, "+8613800138001");
        accountService.resetWorkContacts(ACCOUNT, "Number changed");

        var sms = forClass(SmsRequestMessage.class);
        verify(eventPublisher).publishEvent(sms.capture());
        assertThat(sms.getValue().to()).containsExactly("+8613800138000");
        assertThat(sms.getValue().templateCode()).isEqualTo("user.contact-reset");
        // No email channel existed, so no mail — but the person is not left uninformed.
        verify(eventPublisher, never()).publishEvent(any(MailRequestMessage.class));
    }
}
