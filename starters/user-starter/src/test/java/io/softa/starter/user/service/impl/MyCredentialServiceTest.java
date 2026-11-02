package io.softa.starter.user.service.impl;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.message.SmsRequestMessage;
import io.softa.framework.orm.service.CacheService;
import io.softa.framework.orm.service.TenantInfoService;
import io.softa.framework.web.session.SessionRevokeReason;
import io.softa.starter.user.constant.AccountErrorCodes;
import io.softa.starter.user.dto.MembershipOption;
import io.softa.starter.user.dto.MyAccountSummary;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.entity.UserIdentity;
import io.softa.starter.user.entity.UserSecurityLog;
import io.softa.starter.user.enums.AccountStatus;
import io.softa.starter.user.enums.SecurityAction;
import io.softa.starter.user.service.LoginService;
import io.softa.starter.user.service.UserAccountService;
import io.softa.starter.user.service.UserIdentityService;
import io.softa.starter.user.service.UserSecurityLogService;
import io.softa.starter.user.service.UserSessionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The signed-in person changing their own password and login mobile: each step spends the ticket of
 * the one before, the change holds for the person in every company, and every other device is
 * signed out while this one stays.
 */
class MyCredentialServiceTest {

    private static final Long ACCOUNT = 100L;
    private static final Long ADA = 7L;
    private static final String MOBILE = "+6591234567";
    private static final String NEW_MOBILE = "+6598765432";
    private static final String SESSION = "this-device";

    private final Map<String, String> cache = new HashMap<>();
    private final UserAccountService accountService = mock(UserAccountService.class);
    private final UserIdentityService identityService = mock(UserIdentityService.class);
    private final LoginService loginService = mock(LoginService.class);
    private final VerificationCodeGuard codeGuard = mock(VerificationCodeGuard.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final UserSessionService sessions = mock(UserSessionService.class);
    private final UserSecurityLogService securityLog = mock(UserSecurityLogService.class);
    private final LoginHistoryRecorder history = mock(LoginHistoryRecorder.class);
    private final TenantInfoService tenants = mock(TenantInfoService.class);
    private final MyCredentialServiceImpl service = new MyCredentialServiceImpl();
    private final UserAccount account = new UserAccount();
    private final UserIdentity ada = new UserIdentity();

    MyCredentialServiceTest() {
        ReflectionTestUtils.setField(service, "accountService", accountService);
        ReflectionTestUtils.setField(service, "identityService", identityService);
        ReflectionTestUtils.setField(service, "loginService", loginService);
        ReflectionTestUtils.setField(service, "codeGuard", codeGuard);
        ReflectionTestUtils.setField(service, "cacheService", inMemoryCache());
        ReflectionTestUtils.setField(service, "eventPublisher", events);
        ReflectionTestUtils.setField(service, "sessionService", sessions);
        ReflectionTestUtils.setField(service, "securityLog", securityLog);
        ReflectionTestUtils.setField(service, "history", history);
        ReflectionTestUtils.setField(service, "mobileFormat", InvitationServiceTestSupport.mobileFormat());
        ReflectionTestUtils.setField(service, "tenantInfoService", tenants);

        account.setId(ACCOUNT);
        account.setTenantId(1L);
        account.setProfileId(ADA);
        account.setStatus(AccountStatus.ACTIVE);
        account.setEmail("ada@acme.com");
        ada.setId(11L);
        ada.setProfileId(ADA);
        ada.setLoginMobile(MOBILE);
        when(accountService.getById(ACCOUNT)).thenReturn(Optional.of(account));
        when(identityService.findByProfile(ADA)).thenReturn(Optional.of(ada));
        when(identityService.findByLoginIdentifier(anyString())).thenReturn(Optional.empty());
        when(sessions.currentSessionId()).thenReturn(SESSION);
        when(securityLog.entry(any(), any(), any(), any())).thenAnswer(invocation -> {
            UserSecurityLog row = new UserSecurityLog();
            row.setAction(invocation.getArgument(0));
            return row;
        });
    }

    private CacheService inMemoryCache() {
        CacheService cacheService = mock(CacheService.class);
        doAnswer(inv -> cache.put(inv.getArgument(0), inv.getArgument(1).toString()))
                .when(cacheService).save(anyString(), any(), anyInt());
        when(cacheService.get(anyString())).thenAnswer(inv -> cache.get(inv.<String>getArgument(0)));
        doAnswer(inv -> cache.remove(inv.<String>getArgument(0))).when(cacheService).clear(anyString());
        return cacheService;
    }

    private <T> T signedIn(Supplier<T> action) {
        Context context = new Context();
        context.setUserId(ACCOUNT);
        context.setTenantId(1L);
        return ContextHolder.callWith(context, action::get);
    }

    private void signedIn(Runnable action) {
        this.signedIn(() -> {
            action.run();
            return null;
        });
    }

    private static String errorCodeOf(Throwable e) {
        return ((BusinessException) e).getErrorCode();
    }

    // ── password ────────────────────────────────────────────────────

    @Test
    void theCodeGoesToTheLoginMobile_underTheSharedLimits() {
        signedIn(service::sendCurrentMobileCode);

        verify(codeGuard).beforeSend(MOBILE);
        verify(codeGuard).store(eq(MOBILE), anyString());
        verify(events).publishEvent(any(SmsRequestMessage.class));
    }

    @Test
    void changingThePassword_signsOutEveryOtherDevice_keepsThisOne_andIsLogged() {
        String ticket = signedIn(() -> service.verifyCodeForPassword("123456"));
        verify(codeGuard).verify(MOBILE, "123456");

        signedIn(() -> service.changePassword(ticket, "N3w-Passw0rd!"));

        verify(identityService).setPassword(11L, "N3w-Passw0rd!");
        verify(sessions).revokePerson(ADA, SESSION, SessionRevokeReason.KICKED_BY_OTHER_DEVICE);
        verify(securityLog).entry(eq(SecurityAction.PASSWORD_CHANGED), any(), eq(account), any());
        // Spent: a ticket buys one change.
        assertThatThrownBy(() -> signedIn(() -> service.changePassword(ticket, "An0ther-Passw0rd!")))
                .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo(AccountErrorCodes.TICKET_EXPIRED));
    }

    @Test
    void aTicketForAnotherStep_doesNotChangeThePassword() {
        String mobileTicket = signedIn(() -> service.verifyCurrentForMobile("code", "123456", null));

        assertThatThrownBy(() -> signedIn(() -> service.changePassword(mobileTicket, "N3w-Passw0rd!")))
                .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo(AccountErrorCodes.TICKET_EXPIRED));
        verify(identityService, never()).setPassword(anyLong(), anyString());
    }

    @Test
    void aTicketFromAnotherAccount_isRefused() {
        String ticket = signedIn(() -> service.verifyCodeForPassword("123456"));
        UserAccount other = new UserAccount();
        other.setId(200L);
        other.setProfileId(ADA);
        when(accountService.getById(200L)).thenReturn(Optional.of(other));
        Context context = new Context();
        context.setUserId(200L);

        assertThatThrownBy(() -> ContextHolder.runWith(context, () -> service.changePassword(ticket, "N3w-Passw0rd!")))
                .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo(AccountErrorCodes.TICKET_EXPIRED));
    }

    @Test
    void aPersonWithNoLoginMobile_isToldSo() {
        ada.setLoginMobile(null);

        assertThatThrownBy(() -> signedIn(service::sendCurrentMobileCode))
                .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo(AccountErrorCodes.MOBILE_NOT_SET));
    }

    // ── login mobile ────────────────────────────────────────────────

    @Test
    void aLockedPassword_cannotProveItIsThem() {
        when(identityService.isPasswordLocked(ada)).thenReturn(true);

        assertThatThrownBy(() -> signedIn(() -> service.verifyCurrentForMobile("password", null, "whatever")))
                .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo(AccountErrorCodes.PASSWORD_LOCKED));
        verify(identityService, never()).matchesPassword(any(), anyString());
    }

    @Test
    void aWrongPassword_countsTowardTheSameLockAsSigningIn() {
        when(identityService.recordPasswordFailure(ada)).thenReturn(8L);

        assertThatThrownBy(() -> signedIn(() -> service.verifyCurrentForMobile("password", null, "wrong")))
                .satisfies(e -> {
                    assertThat(errorCodeOf(e)).isEqualTo(AccountErrorCodes.PASSWORD_INCORRECT);
                    assertThat(((BusinessException) e).getErrorDetails()).containsEntry("remainingAttempts", 2L);
                });
    }

    @Test
    void theNewNumber_mustDifferFromTheCurrentOne() {
        String ticket = signedIn(() -> service.verifyCurrentForMobile("code", "123456", null));

        assertThatThrownBy(() -> signedIn(() -> service.checkNewMobile(ticket, "+65 9123 4567")))
                .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo(AccountErrorCodes.MOBILE_UNCHANGED));
        // Still usable: the person corrects the number and tries again.
        assertThat(signedIn(() -> service.checkNewMobile(ticket, NEW_MOBILE))).isNotBlank();
    }

    @Test
    void theNewNumber_ofSomeoneStillInACompany_isTaken() {
        UserIdentity holder = new UserIdentity();
        holder.setProfileId(9L);
        when(identityService.findByLoginIdentifier(NEW_MOBILE)).thenReturn(Optional.of(holder));
        String ticket = signedIn(() -> service.verifyCurrentForMobile("code", "123456", null));

        assertThatThrownBy(() -> signedIn(() -> service.checkNewMobile(ticket, NEW_MOBILE)))
                .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo(AccountErrorCodes.MOBILE_TAKEN));
        verify(codeGuard, never()).beforeSend(NEW_MOBILE);
    }

    @Test
    void theNewNumber_ofADormantPerson_isReleasedOnConfirm_andTheChangeHoldsEverywhere() {
        UserIdentity dormant = new UserIdentity();
        dormant.setProfileId(9L);
        when(identityService.findByLoginIdentifier(NEW_MOBILE)).thenReturn(Optional.of(dormant));
        when(accountService.isDormantLoginMobile(NEW_MOBILE)).thenReturn(true);
        when(accountService.releaseDormantLoginMobile(eq(NEW_MOBILE), anyString())).thenReturn(true);
        String verified = signedIn(() -> service.verifyCurrentForMobile("code", "123456", null));
        String confirm = signedIn(() -> service.checkNewMobile(verified, NEW_MOBILE));
        // Nothing released before the new owner proves they hold the number.
        verify(accountService, never()).releaseDormantLoginMobile(anyString(), anyString());
        verify(codeGuard).beforeSend(NEW_MOBILE);

        signedIn(() -> service.resendNewMobileCode(confirm));
        signedIn(() -> service.confirmNewMobile(confirm, "654321"));

        verify(codeGuard).verify(NEW_MOBILE, "654321");
        verify(accountService).releaseDormantLoginMobile(eq(NEW_MOBILE), anyString());
        assertThat(ada.getLoginMobile()).isEqualTo(NEW_MOBILE);
        verify(identityService).updateOne(ada);
        verify(accountService).followLoginMobile(ADA);
        verify(sessions).revokePerson(ADA, SESSION, SessionRevokeReason.KICKED_BY_OTHER_DEVICE);
        verify(securityLog).entry(eq(SecurityAction.LOGIN_MOBILE_CHANGED), any(), eq(account), any());
        // Both numbers masked as shown everywhere: dial code, space, first three, four stars, last two.
        org.mockito.ArgumentCaptor<UserSecurityLog> rows = org.mockito.ArgumentCaptor.forClass(UserSecurityLog.class);
        verify(securityLog).record(rows.capture());
        assertThat(rows.getValue().getOldValue()).isEqualTo("+65 912****67");
        assertThat(rows.getValue().getNewValue()).isEqualTo("+65 987****32");
    }

    @Test
    void eachStepsTicket_opensOnlyTheNextStep() {
        String verified = signedIn(() -> service.verifyCurrentForMobile("code", "123456", null));

        assertThatThrownBy(() -> signedIn(() -> service.confirmNewMobile(verified, "654321")))
                .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo(AccountErrorCodes.TICKET_EXPIRED));
        assertThat(ada.getLoginMobile()).isEqualTo(MOBILE);
    }

    // ── summary ─────────────────────────────────────────────────────

    @Test
    void theSummary_masksTheContacts_andCountsTheCompaniesInThePicker() {
        when(tenants.getTenantName(1L)).thenReturn("Acme");
        when(loginService.myTenants(ACCOUNT)).thenReturn(List.of(
                new MembershipOption(ACCOUNT, 1L, "Acme", AccountStatus.LOCKED, true, false, null, true),
                new MembershipOption(200L, 2L, "Globex", AccountStatus.FROZEN, true, false, null, false)));

        MyAccountSummary summary = signedIn(service::summary);

        assertThat(summary.maskedLoginMobile()).isEqualTo("+65 912****67");
        assertThat(summary.maskedEmail()).isEqualTo("a***@acme.com");
        assertThat(summary.tenantName()).isEqualTo("Acme");
        assertThat(summary.tenantCount()).isEqualTo(2);
        assertThat(summary.accountStatus()).isEqualTo(AccountStatus.LOCKED);
    }

    @Test
    void aCallerWithNoRoleAtAll_stillGetsTheirSummary_andChangesTheirPassword() {
        // A new employee who just joined holds no role, so their data scope reaches no account: any
        // read of their own account or identity under the permission check is refused with 403.
        when(accountService.getById(ACCOUNT)).thenAnswer(inv -> {
            refuseUnlessUnchecked();
            return Optional.of(account);
        });
        when(identityService.findByProfile(ADA)).thenAnswer(inv -> {
            refuseUnlessUnchecked();
            return Optional.of(ada);
        });
        when(loginService.myTenants(ACCOUNT)).thenAnswer(inv -> {
            refuseUnlessUnchecked();
            return List.of(new MembershipOption(ACCOUNT, 1L, "Acme", AccountStatus.ACTIVE, false, false, null, true));
        });

        MyAccountSummary summary = signedIn(service::summary);
        String ticket = signedIn(() -> service.verifyCodeForPassword("123456"));
        signedIn(() -> service.changePassword(ticket, "N3w-Passw0rd!"));

        assertThat(summary.tenantCount()).isEqualTo(1);
        assertThat(summary.maskedLoginMobile()).isEqualTo("+65 912****67");
        verify(identityService).setPassword(11L, "N3w-Passw0rd!");
    }

    /** What the permission engine does to a caller whose scope reaches nothing. */
    private static void refuseUnlessUnchecked() {
        if (!ContextHolder.getContext().isSkipPermissionCheck()) {
            throw new io.softa.framework.base.exception.PermissionException(
                    "Some UserAccount ids are outside your READ scope");
        }
    }

    @Test
    void aLiftedLock_isRecordedWhenThePasswordChanges() {
        ada.setPasswordLockedUntil(LocalDateTime.now().plusMinutes(5));
        when(identityService.isPasswordLocked(ada)).thenReturn(true);
        String ticket = signedIn(() -> service.verifyCodeForPassword("123456"));

        signedIn(() -> service.changePassword(ticket, "N3w-Passw0rd!"));

        verify(history).record(eq(io.softa.starter.user.enums.LoginEvent.UNLOCKED), eq(ADA), any(), any(),
                any(), any(), any());
    }
}
