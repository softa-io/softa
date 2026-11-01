package io.softa.starter.user.service.impl;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.exception.BusinessException;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.entity.UserIdentity;
import io.softa.starter.user.enums.AccountStatus;
import io.softa.starter.user.service.UserIdentityService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Freeze / Unfreeze, which replaced Lock / Unlock.
 *
 * <p>A manual lock and the automatic password lockout were two mechanisms for two different
 * things wearing one name: the lockout reacts to guessing, lives on the credential and expires by
 * itself, while freezing is an administrator's decision about a membership that only an
 * administrator lifts. The cell that matters most is Freeze-on-Locked: it clears the password lock
 * on the way through, or lifting the freeze hands back an account the lockout still refuses and the
 * administrator who lifted it cannot see why.
 */
class FreezeAccountTest {

    private static final Long ACCOUNT = 100L;
    private static final Long PROFILE = 7L;

    private final UserIdentityService identityService = mock(UserIdentityService.class);
    private final UserAccountServiceImpl accountService = spy(new UserAccountServiceImpl());
    private final io.softa.starter.user.service.UserSecurityLogService securityLog =
            AccountServiceTestSupport.quiet(accountService);

    FreezeAccountTest() {
        ReflectionTestUtils.setField(accountService, "identityService", identityService);
    }

    private UserAccount given(AccountStatus status) {
        UserAccount account = new UserAccount();
        account.setId(ACCOUNT);
        account.setProfileId(PROFILE);
        account.setStatus(status);
        doReturn(Optional.of(account)).when(accountService).getById(ACCOUNT);
        doReturn(true).when(accountService).updateOne(any(UserAccount.class));
        when(identityService.findByProfile(PROFILE)).thenReturn(Optional.empty());
        return account;
    }

    @Test
    void anActiveMembershipCanBeFrozen() {
        UserAccount account = given(AccountStatus.ACTIVE);

        accountService.freezeAccount(ACCOUNT, "Under investigation");

        assertThat(account.getStatus()).isEqualTo(AccountStatus.FROZEN);
    }

    @Test
    void freezing_dropsTheAccountsPushDevices() {
        // A frozen company must not keep notifying the person; the sessions end on their next
        // request, but a device that never asks again would go on receiving pushes.
        given(AccountStatus.ACTIVE);
        io.softa.starter.user.spi.PushDeviceRegistry devices = mock(io.softa.starter.user.spi.PushDeviceRegistry.class);
        ReflectionTestUtils.setField(accountService, "pushDeviceRegistry", devices);

        accountService.freezeAccount(ACCOUNT, "Under investigation");

        verify(devices).unregisterAccount(ACCOUNT);
    }

    @Test
    void freezingALockedAccount_clearsThePasswordLockOnTheWayThrough() {
        UserAccount account = given(AccountStatus.LOCKED);
        UserIdentity person = new UserIdentity();
        person.setId(11L);
        person.setProfileId(PROFILE);
        person.setPasswordLockedUntil(LocalDateTime.now().plusMinutes(20));
        when(identityService.findByProfile(PROFILE)).thenReturn(Optional.of(person));

        accountService.freezeAccount(ACCOUNT, "Under investigation");

        assertThat(account.getStatus()).isEqualTo(AccountStatus.FROZEN);
        assertThat(person.getPasswordLockedUntil()).isNull();
        // Writing a null, so the overload that keeps them is not optional.
        verify(identityService).updateOne(person, false);
        verify(identityService).clearPasswordFailures(11L);
    }

    @Test
    void freezingIsIdempotent() {
        UserAccount account = given(AccountStatus.FROZEN);

        assertThatCode(() -> accountService.freezeAccount(ACCOUNT, "again"))
                .doesNotThrowAnyException();
        assertThat(account.getStatus()).isEqualTo(AccountStatus.FROZEN);
    }

    @Test
    void anInvitedOrOffBoardedMembershipCannotBeFrozen() {
        for (AccountStatus status : new AccountStatus[] {
                AccountStatus.PENDING, AccountStatus.INVITED, AccountStatus.DEACTIVATED }) {
            given(status);
            assertThatThrownBy(() -> accountService.freezeAccount(ACCOUNT, "x"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Only an active account can be frozen");
        }
    }

    @Test
    void unfreezingWorksOnlyFromFrozen() {
        // Flipping an INVITED or DEACTIVATED row to ACTIVE here would land it in a state its own
        // flow never reaches: invited-but-active, or off-boarded-but-active.
        UserAccount frozen = given(AccountStatus.FROZEN);
        accountService.unfreezeAccount(ACCOUNT, "Cleared");
        assertThat(frozen.getStatus()).isEqualTo(AccountStatus.ACTIVE);

        given(AccountStatus.INVITED);
        assertThatThrownBy(() -> accountService.unfreezeAccount(ACCOUNT, "x"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Only a frozen account can be unfrozen");
    }
}
