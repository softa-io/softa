package io.softa.starter.user.service.impl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.exception.BusinessException;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.entity.UserIdentity;
import io.softa.starter.user.enums.AccountStatus;
import io.softa.starter.user.service.UserIdentityService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An account's contact edited on the account form. Before this, the edit changed the account row
 * and nothing else: the person's login identifier kept the old address, which went on signing them
 * in — and went on naming them, so creating a new employee with the freed address was refused as
 * "already a member of this company".
 */
class AccountContactEditTest {

    private static final Long ACCOUNT = 100L;
    private static final Long PROFILE = 7L;

    private final UserIdentityService identityService = mock(UserIdentityService.class);
    private final UserAccountServiceImpl accountService = spy(new UserAccountServiceImpl());
    private final io.softa.starter.user.service.UserSecurityLogService securityLog =
            AccountServiceTestSupport.quiet(accountService);

    private final io.softa.starter.user.service.UserInvitationService invitations;

    AccountContactEditTest() {
        ReflectionTestUtils.setField(accountService, "identityService", identityService);
        invitations = (io.softa.starter.user.service.UserInvitationService)
                ReflectionTestUtils.getField(accountService, "invitationService");
        // The person behind an account nobody has joined yet is, by default, the placeholder minted
        // with it; a test about a real person says so.
        when(invitations.isPlaceholderPerson(PROFILE, ACCOUNT)).thenReturn(true);
    }

    private void given(AccountStatus status, String email, String mobile) {
        UserAccount account = new UserAccount();
        account.setId(ACCOUNT);
        account.setProfileId(PROFILE);
        account.setStatus(status);
        account.setEmail(email);
        account.setMobile(mobile);
        doReturn(Optional.of(account)).when(accountService).getById(ACCOUNT);
    }

    private UserIdentity person(String loginEmail, String loginMobile) {
        UserIdentity identity = new UserIdentity();
        identity.setId(11L);
        identity.setProfileId(PROFILE);
        identity.setLoginEmail(loginEmail);
        identity.setLoginMobile(loginMobile);
        when(identityService.findByProfile(PROFILE)).thenReturn(Optional.of(identity));
        when(identityService.findByLoginIdentifier(any())).thenReturn(Optional.empty());
        return identity;
    }

    private static Map<String, Object> patch(Object... kv) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", ACCOUNT);
        for (int i = 0; i < kv.length; i += 2) {
            row.put((String) kv[i], kv[i + 1]);
        }
        return row;
    }

    /** Runs the edit; true when the write itself was reached. */
    private boolean edit(Map<String, Object> row) {
        AtomicBoolean written = new AtomicBoolean();
        accountService.updateCarryingLoginIdentifiers(List.of(row), () -> {
            written.set(true);
            return true;
        });
        return written.get();
    }

    @Test
    void anEditedContactTakesTheLoginIdentifierWithIt() {
        given(AccountStatus.PENDING, "old@acme.com", "+6590000001");
        UserIdentity identity = person("old@acme.com", "+6590000001");

        assertThat(edit(patch("email", "New@Acme.com", "mobile", "+6590000002"))).isTrue();

        // The mobile is stored the way login looks it up; the old number no longer names this person.
        verify(identityService).updateLoginMobile(11L, "+6590000002");
        // The email has no copy on the person to move: it signs in through the account itself.
        assertThat(identity.getLoginEmail()).isEqualTo("old@acme.com");
    }

    @Test
    void aRealPersonsLoginMobile_isNotTheCompanysToMove() {
        // The account was bound to an existing person (HR created it under their contact): their
        // login mobile signs them in everywhere, and an account form must not rewrite it.
        given(AccountStatus.INVITED, null, "+6590000001");
        UserIdentity identity = person(null, "+6590000001");
        when(invitations.isPlaceholderPerson(PROFILE, ACCOUNT)).thenReturn(false);

        assertThat(edit(patch("mobile", "+6590000002"))).isTrue();

        assertThat(identity.getLoginMobile()).isEqualTo("+6590000001");
        verify(identityService, never()).updateLoginMobile(any(), any());
    }

    @Test
    void anEmailOnlyEdit_touchesNoCredential() {
        given(AccountStatus.PENDING, "old@acme.com", null);
        person("old@acme.com", null);

        assertThat(edit(patch("email", "new@acme.com"))).isTrue();

        verify(identityService, never()).updateLoginMobile(any(), any());
        verify(identityService, never()).findByProfile(any());
    }

    @Test
    void aPersonalLoginIsNotTheCompanysToRewrite() {
        given(AccountStatus.INVITED, "old@acme.com", null);
        UserIdentity identity = person("me@gmail.com", null);

        assertThat(edit(patch("email", "new@acme.com"))).isTrue();

        assertThat(identity.getLoginEmail()).isEqualTo("me@gmail.com");
        verify(identityService, never()).updateLoginMobile(any(), any());
    }

    @Test
    void clearingAContactReleasesItsLogin() {
        given(AccountStatus.PENDING, "old@acme.com", "+6590000001");
        UserIdentity identity = person("old@acme.com", "+6590000001");

        assertThat(edit(patch("mobile", null))).isTrue();

        assertThat(identity.getLoginMobile()).isNull();
        assertThat(identity.getLoginEmail()).isEqualTo("old@acme.com");
    }

    @Test
    void anAcceptedAccountChangesContactsThroughResetUser() {
        // Someone signs in with these; Reset User is the path that tells the old address.
        given(AccountStatus.ACTIVE, "old@acme.com", null);
        person("old@acme.com", null);

        assertThatThrownBy(() -> edit(patch("email", "new@acme.com")))
                .isInstanceOf(BusinessException.class)
                .hasMessage(UserAccountServiceImpl.RESET_INSTEAD);
        verify(identityService, never()).updateLoginMobile(any(), any());
    }

    @Test
    void anAcceptedAccountSavedWithItsContactsUnchangedIsNotRefused() {
        // The form posts every field; the same address in another case is not a change.
        given(AccountStatus.ACTIVE, "old@acme.com", "+6590000001");
        person("old@acme.com", "+6590000001");

        assertThat(edit(patch("email", " OLD@acme.com", "mobile", "+6590000001", "nickname", "Ann"))).isTrue();
        verify(identityService, never()).updateLoginMobile(any(), any());
    }

    @Test
    void aNumberAnotherPersonSignsInWithIsRefused() {
        given(AccountStatus.PENDING, null, "+6590000001");
        person(null, "+6590000001");
        UserIdentity someoneElse = new UserIdentity();
        someoneElse.setProfileId(8L);
        when(identityService.findByLoginIdentifier("+6590000009")).thenReturn(Optional.of(someoneElse));

        assertThatThrownBy(() -> edit(patch("mobile", "+6590000009")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already signs in another person");
    }

    @Test
    void aRowNotTouchingContactsIsWrittenWithoutLookingAnythingUp() {
        assertThat(edit(patch("nickname", "Ann"))).isTrue();
        verify(accountService, never()).getById(any());
    }
}
