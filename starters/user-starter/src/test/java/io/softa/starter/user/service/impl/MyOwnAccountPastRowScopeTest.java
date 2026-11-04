package io.softa.starter.user.service.impl;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.ReflectionUtils;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.PermissionException;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.entity.UserIdentity;
import io.softa.starter.user.service.UserIdentityService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The self-service account calls read the caller's own row past row scope.
 *
 * <p>UserAccount has no employee or department anchor, so a role without a grant on it reads no
 * row at all — its own included. Personal settings, change password and set first password all
 * failed with "Some UserAccount ids are outside your READ scope" for every Employee or Manager. The
 * stub below answers only when the scope is skipped, the way the real scope would refuse otherwise.
 */
class MyOwnAccountPastRowScopeTest {

    private static final Long USER = 100L;
    private static final Long PROFILE = 7L;

    private final UserIdentityService identityService = mock(UserIdentityService.class);
    private final UserAccountServiceImpl accountService = spy(new UserAccountServiceImpl());
    private final UserIdentity identity = new UserIdentity();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(accountService, "identityService", identityService);
        // Collaborators a password change also reaches on branches that sign other devices out
        // or record the attempt; mocked by type when present, so this test reads the same either way.
        for (String collaborator : new String[] {"sessionService", "loginHistory"}) {
            java.lang.reflect.Field field = ReflectionUtils.findField(UserAccountServiceImpl.class, collaborator);
            if (field != null) {
                ReflectionTestUtils.setField(accountService, collaborator, mock(field.getType()));
            }
        }
        UserAccount account = new UserAccount();
        account.setId(USER);
        account.setProfileId(PROFILE);
        doAnswer(call -> {
            if (!ContextHolder.getContext().isSkipPermissionCheck()) {
                throw new PermissionException("Some UserAccount ids are outside your READ scope");
            }
            return Optional.of(account);
        }).when(accountService).getById(USER);
        identity.setId(PROFILE + 1000);
        identity.setProfileId(PROFILE);
        when(identityService.findByProfile(PROFILE)).thenReturn(Optional.of(identity));
        when(identityService.requireIdentity(account)).thenReturn(identity);
    }

    private static Context signedInAs(Long userId) {
        Context context = new Context();
        context.setUserId(userId);
        return context;
    }

    @Test
    void getMyOwnAccount_readsTheRowPastScope_andLeavesTheCallersContextAlone() {
        Context context = signedInAs(USER);

        Optional<UserAccount> account = ContextHolder.callWith(context, () -> accountService.getMyOwnAccount(USER));

        assertThat(account).map(UserAccount::getId).contains(USER);
        assertThat(context.isSkipPermissionCheck()).isFalse();
    }

    @Test
    void canSetMyFirstPassword_answersForARoleWithoutAGrant() {
        assertThat(ContextHolder.callWith(signedInAs(USER), accountService::canSetMyFirstPassword)).isTrue();
    }

    @Test
    void changeMyPassword_reachesTheCredentialForARoleWithoutAGrant() {
        identity.setPassword("stored-hash");
        when(identityService.matchesPassword(identity, "old-password")).thenReturn(true);

        ContextHolder.runWith(signedInAs(USER), () -> accountService.changeMyPassword("old-password", "New-password-1"));

        verify(identityService).setPassword(identity.getId(), "New-password-1");
    }

    @Test
    void setMyFirstPassword_reachesTheCredentialForARoleWithoutAGrant() {
        ContextHolder.runWith(signedInAs(USER), () -> accountService.setMyFirstPassword("New-password-1"));

        verify(identityService).setPassword(identity.getId(), "New-password-1");
    }
}
