package io.softa.starter.user.service.impl;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.starter.user.constant.AccountErrorCodes;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.entity.UserLoginHistory;
import io.softa.starter.user.enums.AccountStatus;
import io.softa.starter.user.enums.LoginChannel;
import io.softa.starter.user.enums.LoginEvent;
import io.softa.starter.user.enums.LoginMethod;
import io.softa.starter.user.enums.LoginStatus;
import io.softa.starter.user.service.UserAccountService;
import io.softa.starter.user.service.UserLoginHistoryService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Where a history row lands. A failed sign-in, a lock and an unlock are about the person's password,
 * which every company they work in shares, and they happen before a company is chosen: kept without
 * a tenant, no company's history ever showed one.
 */
class LoginHistoryRecorderTest {

    private static final Long ADA = 7L;

    private final UserLoginHistoryService historyService = mock(UserLoginHistoryService.class);
    private final UserAccountService accountService = mock(UserAccountService.class);
    private final LoginHistoryRecorder recorder = new LoginHistoryRecorder();

    LoginHistoryRecorderTest() {
        ReflectionTestUtils.setField(recorder, "historyService", historyService);
        ReflectionTestUtils.setField(recorder, "accountService", accountService);
    }

    private static UserAccount membership(Long id, Long tenantId, AccountStatus status) {
        UserAccount account = new UserAccount();
        account.setId(id);
        account.setTenantId(tenantId);
        account.setProfileId(ADA);
        account.setStatus(status);
        return account;
    }

    private List<UserLoginHistory> written() {
        ArgumentCaptor<UserLoginHistory> rows = ArgumentCaptor.forClass(UserLoginHistory.class);
        verify(historyService, atLeastOnce()).record(rows.capture());
        return rows.getAllValues();
    }

    @Test
    void aFailedSignInOfAKnownPerson_isWrittenToEachCompanyTheyWorkIn_underTheirAccountThere() {
        when(accountService.listMembershipsOf(ADA)).thenReturn(List.of(
                membership(100L, 1L, AccountStatus.ACTIVE),
                membership(200L, 2L, AccountStatus.FROZEN),
                // Not joined yet, or already left: not a company they work in.
                membership(300L, 3L, AccountStatus.INVITED),
                membership(400L, 4L, AccountStatus.DEACTIVATED)));

        recorder.record(LoginEvent.LOGIN_FAILED, ADA, null, null, LoginChannel.MOBILE, LoginMethod.PASSWORD,
                "PASSWORD_INCORRECT");

        assertThat(written()).extracting(UserLoginHistory::getTenantId).containsExactly(1L, 2L);
        assertThat(written()).extracting(UserLoginHistory::getUserId).containsExactly(100L, 200L);
        assertThat(written()).allSatisfy(row -> {
            assertThat(row.getEvent()).isEqualTo(LoginEvent.LOGIN_FAILED);
            assertThat(row.getStatus()).isEqualTo(LoginStatus.INVALID);
            assertThat(row.getFailureReason()).isEqualTo("PASSWORD_INCORRECT");
            assertThat(row.getProfileId()).isEqualTo(ADA);
        });
    }

    @Test
    void aConsultancyAndThePlatformTenant_areNotCompaniesTheyWorkIn() {
        UserAccount consultancy = membership(500L, 5L, AccountStatus.ACTIVE);
        consultancy.setConsultant(true);
        when(accountService.listMembershipsOf(ADA)).thenReturn(List.of(
                membership(100L, 1L, AccountStatus.ACTIVE),
                consultancy,
                membership(600L, io.softa.framework.base.constant.BaseConstant.PLATFORM_TENANT_ID,
                        AccountStatus.ACTIVE)));

        recorder.record(LoginEvent.LOGIN_FAILED, ADA, null, null, LoginChannel.MOBILE, LoginMethod.PASSWORD,
                "PASSWORD_INCORRECT");

        // A customer does not see how the consultant serving it signs in.
        assertThat(written()).extracting(UserLoginHistory::getTenantId).containsExactly(1L);
    }

    @Test
    void aLock_isWrittenToEachCompanyToo() {
        when(accountService.listMembershipsOf(ADA)).thenReturn(List.of(
                membership(100L, 1L, AccountStatus.ACTIVE), membership(200L, 2L, AccountStatus.ACTIVE)));

        recorder.record(LoginEvent.LOCKED, ADA, null, null, LoginChannel.MOBILE, LoginMethod.PASSWORD, null);

        assertThat(written()).extracting(UserLoginHistory::getTenantId).containsExactly(1L, 2L);
    }

    @Test
    void anIdentifierThatIsNobodys_namesNoCompany_andStaysWithThePlatform() {
        recorder.record(LoginEvent.LOGIN_FAILED, null, null, null, LoginChannel.MOBILE, LoginMethod.PASSWORD,
                "ACCOUNT_NOT_FOUND");

        assertThat(written()).singleElement().satisfies(row -> {
            assertThat(row.getTenantId()).isNull();
            assertThat(row.getUserId()).isNull();
        });
    }

    @Test
    void aSignInThatStoppedAtTheCompanyPicker_isNotClaimedForEveryCompany() {
        when(accountService.listMembershipsOf(ADA)).thenReturn(List.of(
                membership(100L, 1L, AccountStatus.ACTIVE), membership(200L, 2L, AccountStatus.ACTIVE)));

        recorder.record(LoginEvent.LOGIN_SUCCESS, ADA, null, null, LoginChannel.MOBILE, LoginMethod.PASSWORD, null);

        assertThat(written()).singleElement().extracting(UserLoginHistory::getTenantId).isNull();
    }

    @Test
    void anEventInOneCompany_isWrittenThereOnly() {
        recorder.record(LoginEvent.LOCKED, ADA, 100L, 1L, LoginChannel.MOBILE, LoginMethod.PASSWORD, null);

        assertThat(written()).singleElement().satisfies(row -> {
            assertThat(row.getTenantId()).isEqualTo(1L);
            assertThat(row.getUserId()).isEqualTo(100L);
        });
    }

    @Test
    void companiesThatCannotBeRead_leaveTheRowUnattributed_notUnwritten() {
        when(accountService.listMembershipsOf(ADA)).thenThrow(new IllegalStateException("database down"));

        recorder.record(LoginEvent.LOGIN_FAILED, ADA, null, null, LoginChannel.MOBILE, LoginMethod.PASSWORD,
                "PASSWORD_INCORRECT");

        assertThat(written()).singleElement().extracting(UserLoginHistory::getTenantId).isNull();
    }

    @Test
    void anAttemptRefusedWhileThePasswordIsLocked_isKeptOnceWithThePlatform_notInEveryCompany() {
        // Repeatable by anyone who knows the identifier, and it changes nothing: fanned out, every
        // try would be multiplied into every company's history.
        when(accountService.listMembershipsOf(ADA)).thenReturn(List.of(
                membership(100L, 1L, AccountStatus.ACTIVE),
                membership(200L, 2L, AccountStatus.ACTIVE)));

        recorder.record(LoginEvent.LOGIN_FAILED, ADA, null, null, LoginChannel.MOBILE, LoginMethod.PASSWORD,
                AccountErrorCodes.PASSWORD_LOCKED);

        assertThat(written()).singleElement().satisfies(row -> {
            assertThat(row.getTenantId()).isNull();
            assertThat(row.getUserId()).isNull();
            assertThat(row.getFailureReason()).isEqualTo(AccountErrorCodes.PASSWORD_LOCKED);
        });
    }

    @Test
    void whatChangesThePasswordsState_isStillWrittenToEachCompany() {
        when(accountService.listMembershipsOf(ADA)).thenReturn(List.of(
                membership(100L, 1L, AccountStatus.ACTIVE),
                membership(200L, 2L, AccountStatus.ACTIVE)));

        recorder.record(LoginEvent.LOCKED, ADA, null, null, LoginChannel.MOBILE, LoginMethod.PASSWORD,
                AccountErrorCodes.PASSWORD_LOCKED);
        recorder.record(LoginEvent.UNLOCKED, ADA, null, null, LoginChannel.MOBILE, LoginMethod.PASSWORD, null);

        assertThat(written()).extracting(UserLoginHistory::getTenantId).containsExactly(1L, 2L, 1L, 2L);
    }

    @Test
    void eachCompanysRow_isWrittenAsThatCompany_thoughTheSignInIsInNone() {
        // The write refuses a row of another tenant than the context's: written as the sign-in's own
        // tenantless context, every fanned-out row was refused and only a warning was left.
        when(accountService.listMembershipsOf(ADA)).thenReturn(List.of(
                membership(100L, 1L, AccountStatus.ACTIVE),
                membership(200L, 2L, AccountStatus.ACTIVE)));
        List<Long> writtenAs = new ArrayList<>();
        List<Boolean> pastPermissions = new ArrayList<>();
        doAnswer(invocation -> {
            writtenAs.add(ContextHolder.getContext().getTenantId());
            pastPermissions.add(ContextHolder.getContext().isSkipPermissionCheck());
            return null;
        }).when(historyService).record(any());

        Context signIn = new Context();
        signIn.setTraceId("sign-in");
        ContextHolder.runWith(signIn, () -> recorder.record(LoginEvent.LOGIN_FAILED, ADA, null, null,
                LoginChannel.MOBILE, LoginMethod.PASSWORD, "PASSWORD_INCORRECT"));

        assertThat(writtenAs).containsExactly(1L, 2L);
        assertThat(pastPermissions).containsExactly(true, true);
        // The caller's own context is left as it was.
        assertThat(signIn.getTenantId()).isNull();
        assertThat(signIn.isSkipPermissionCheck()).isFalse();
    }

    @Test
    void aRowOfTheCompanyTheCallerIsIn_isWrittenAsTheCallerWithTheirOwnPermissions() {
        boolean[] skipped = {true};
        doAnswer(invocation -> {
            skipped[0] = ContextHolder.getContext().isSkipPermissionCheck();
            return null;
        }).when(historyService).record(any());

        Context inCompany = new Context();
        inCompany.setTenantId(1L);
        ContextHolder.runWith(inCompany, () -> recorder.record(LoginEvent.LOGIN_SUCCESS, ADA, 100L, 1L,
                LoginChannel.MOBILE, LoginMethod.PASSWORD, null));

        assertThat(skipped[0]).isFalse();
    }
}
