package io.softa.starter.user.service.impl;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.message.MailRequestMessage;
import io.softa.framework.base.message.SmsRequestMessage;
import io.softa.framework.orm.service.CacheService;
import io.softa.starter.user.entity.UserIdentity;
import io.softa.starter.user.service.ConsultantService;
import io.softa.starter.user.service.UserAccountService;
import io.softa.starter.user.service.UserIdentityService;
import io.softa.starter.user.service.UserInvitationService;
import io.softa.starter.user.service.UserSessionService;
import io.softa.starter.user.util.LoginIdentifiers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Nothing reachable before sign-in may answer whether a number or address is anybody's.
 *
 * <p>These entry points are unauthenticated, so any difference between the two outcomes is an
 * oracle: ask once per number and it returns a verified roster of the people an organisation
 * employs. The screen says the same thing either way, and the person who mistyped is served by its
 * wording ("if this number is linked, a code is on its way").
 *
 * <p>Sameness is not one property, which is why this is not one test. The response, the status, the
 * send budget, the countdown and the lock each leak it independently: close three and the fourth
 * still answers the question.
 *
 * <p>What stays different is the only thing that may: nothing is sent to a number nobody holds.
 */
class UnknownIdentifierIsIndistinguishableTest {

    private static final Long PROFILE = 7L;

    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final VerificationCodeGuard codeGuard = mock(VerificationCodeGuard.class);
    private final UserIdentityService identityService = mock(UserIdentityService.class);
    private final UserAccountService accountService = mock(UserAccountService.class);
    private final LoginServiceImpl loginService = new LoginServiceImpl();

    UnknownIdentifierIsIndistinguishableTest() {
        ReflectionTestUtils.setField(loginService, "eventPublisher", eventPublisher);
        ReflectionTestUtils.setField(loginService, "consultantService", mock(ConsultantService.class));
        ReflectionTestUtils.setField(loginService, "codeGuard", codeGuard);
        ReflectionTestUtils.setField(loginService, "identityService", identityService);
        ReflectionTestUtils.setField(loginService, "accountService", accountService);
        ReflectionTestUtils.setField(loginService, "history", mock(LoginHistoryRecorder.class));
        ReflectionTestUtils.setField(loginService, "cacheService", mock(CacheService.class));
        ReflectionTestUtils.setField(loginService, "sessionService", mock(UserSessionService.class));
        when(accountService.listAccountIdsOf(PROFILE)).thenReturn(List.of(100L));
    }

    @AfterEach
    void clearRequest() {
        ClientRequests.clear();
    }

    private void nobodyHolds() {
        when(identityService.findByLoginIdentifier(anyString())).thenReturn(Optional.empty());
    }

    /**
     * Stubbed on the TYPED spelling, not on {@code anyString()}: {@code normalize} strips a mobile's
     * separators and would miss a row seeded with the separators the person still writes. Bound
     * loosely, swapping the lookup to {@code normalize} leaves the suite green — see
     * {@link #aLegacySpelledNumberIsStillFound}.
     */
    private UserIdentity somebodyHolds(String identifier) {
        UserIdentity identity = new UserIdentity();
        identity.setId(11L);
        identity.setProfileId(PROFILE);
        when(identityService.findByLoginIdentifier(LoginIdentifiers.typedForm(identifier)))
                .thenReturn(Optional.of(identity));
        return identity;
    }

    /** What the caller can see of a refusal: its code, its sentence and its details. */
    private static void sameAnswer(Throwable known, Throwable unknown) {
        assertThat(unknown).as("known threw %s", known).isInstanceOf(BusinessException.class);
        assertThat(known).isInstanceOf(BusinessException.class);
        BusinessException k = (BusinessException) known;
        BusinessException u = (BusinessException) unknown;
        assertThat(u.getErrorCode()).isEqualTo(k.getErrorCode());
        assertThat(u.getMessage()).isEqualTo(k.getMessage());
        assertThat(u.getErrorDetails()).isEqualTo(k.getErrorDetails());
    }

    // ───────────────────────── asking for a code ─────────────────────────

    @Test
    void anUnknownEmailIsAcceptedAsQuietlyAsAKnownOne() {
        nobodyHolds();

        assertThatCode(() -> loginService.sendEmailCode("nobody@nowhere.test"))
                .as("a refusal here — however it is worded — is the oracle itself")
                .doesNotThrowAnyException();
    }

    @Test
    void anUnknownMobileIsAcceptedAsQuietlyAsAKnownOne() {
        nobodyHolds();

        assertThatCode(() -> loginService.sendMobileCode("+8613900000000"))
                .doesNotThrowAnyException();
    }

    @Test
    void nothingIsSentToANumberNobodyHolds() {
        nobodyHolds();

        loginService.sendEmailCode("nobody@nowhere.test");

        // any(Object.class), not any(): publishEvent is overloaded and a bare any() binds to the
        // ApplicationEvent form, which nothing here ever calls — the check would pass on its own.
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void anUnknownNumberSpendsItsSendAllowanceJustTheSame() {
        // Otherwise the limiter is the oracle one step along: the eleventh ask of one number is
        // refused when it exists and accepted when it does not.
        nobodyHolds();

        loginService.sendEmailCode("nobody@nowhere.test");

        verify(codeGuard).beforeSend("nobody@nowhere.test");
    }

    @Test
    void runningOutOfSendsLooksTheSameWhetherOrNotTheNumberExists() {
        doThrow(new BusinessException("Too many requests")).when(codeGuard).beforeSend(anyString());

        somebodyHolds("alice@acme.com");
        Throwable linked = catchThrowable(() -> loginService.sendEmailCode("alice@acme.com"));
        nobodyHolds();
        Throwable unlinked = catchThrowable(() -> loginService.sendEmailCode("nobody@nowhere.test"));

        assertThat(linked).as("linked threw %s; unlinked threw %s", linked, unlinked).isNull();
        assertThat(unlinked).isNull();
    }

    @Test
    void afterManyMissesFromOneAddress_aRealNumberAndAMadeUpOneStillGetTheSameAnswer() {
        // A per-address allowance of MISSES, refused once spent, is the oracle one step later: past
        // it a made-up number is refused while a real one still goes through. So there is none —
        // whatever bounds an address has to bound hits and misses alike.
        ClientRequests.fromAddress("203.0.113.9");
        nobodyHolds();
        for (int i = 0; i < 100; i++) {
            String number = "+65900000" + String.format("%02d", i);
            assertThatCode(() -> loginService.sendMobileCode(number)).doesNotThrowAnyException();
        }

        somebodyHolds("+6591234567");
        Throwable real = catchThrowable(() -> loginService.sendMobileCode("+6591234567"));
        Throwable madeUp = catchThrowable(() -> loginService.sendMobileCode("+6590000999"));

        assertThat(real).isNull();
        assertThat(madeUp).isNull();
        verify(eventPublisher).publishEvent(any(SmsRequestMessage.class));
    }

    @Test
    void aPersonWithNoAccountIsAnsweredLikeNobody() {
        // An identity no account was ever created for has nowhere to sign in; nothing goes out, and
        // the answer is the same.
        somebodyHolds("ghost@acme.com");
        when(accountService.listAccountIdsOf(PROFILE)).thenReturn(List.of());

        assertThatCode(() -> loginService.sendEmailCode("ghost@acme.com")).doesNotThrowAnyException();
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void aLegacySpelledNumberIsStillFound() {
        // Separators on purpose — with none, the typed and canonical spellings coincide and the case
        // proves nothing.
        somebodyHolds("+86 138-0013-8000");

        loginService.sendMobileCode("+86 138-0013-8000");

        verify(eventPublisher).publishEvent(any(SmsRequestMessage.class));
    }

    // ───────────────────────── /join is not this ─────────────────────────

    @Test
    void joinStillSendsToSomebodyWhoHasNoIdentityYet() {
        nobodyHolds();
        UserInvitationService invitations = mock(UserInvitationService.class);
        when(invitations.resolveJoinChannel("tok", "email")).thenReturn("newcomer@acme.com");
        ReflectionTestUtils.setField(loginService, "invitationService", invitations);

        loginService.sendJoinCode("tok", "email");

        ArgumentCaptor<MailRequestMessage> sent = ArgumentCaptor.forClass(MailRequestMessage.class);
        verify(eventPublisher).publishEvent(sent.capture());
        assertThat(sent.getValue().to()).containsExactly("newcomer@acme.com");
    }

    @Test
    void joinStaysLoudWhenItCannotSendAtAll() {
        // The swallow lives at the public entry points, not in the delivery: an invitee over the
        // send limit has no enumeration to offer — the caller IS the invitation.
        doThrow(new BusinessException("Too many requests")).when(codeGuard).beforeSend(anyString());
        UserInvitationService invitations = mock(UserInvitationService.class);
        when(invitations.resolveJoinChannel("tok", "email")).thenReturn("newcomer@acme.com");
        ReflectionTestUtils.setField(loginService, "invitationService", invitations);

        assertThatThrownBy(() -> loginService.sendJoinCode("tok", "email"))
                .isInstanceOf(BusinessException.class);
    }

    // ───────────────────────── signing in with a password ─────────────────────────

    /** The n-th wrong password for a real person and for a made-up number, side by side. */
    private void theNthWrongPassword(long n, Consumer<Throwable[]> check) {
        UserIdentity alice = somebodyHolds("alice@acme.com");
        when(identityService.matchesPassword(alice, "wrong")).thenReturn(false);
        when(identityService.recordPasswordFailure(alice)).thenReturn(n);
        when(identityService.recordUnknownIdentifierFailure(anyString())).thenReturn(n);

        Throwable known = catchThrowable(() -> loginService.authenticateByPassword("alice@acme.com", "wrong"));
        Throwable unknown = catchThrowable(() -> loginService.authenticateByPassword("nobody@acme.com", "wrong"));
        check.accept(new Throwable[] {known, unknown});
    }

    @Test
    void aWrongPasswordForNobody_isAWrongPassword() {
        theNthWrongPassword(1, t -> {
            sameAnswer(t[0], t[1]);
            assertThat(((BusinessException) t[1]).getErrorCode()).isEqualTo("PASSWORD_INCORRECT");
        });
    }

    @Test
    void theCountdownRunsForNobodyToo() {
        // The real refusal names the attempts left from the seventh; a number that never counted
        // down would confirm it is nobody's as surely as "no such account".
        theNthWrongPassword(7, t -> {
            sameAnswer(t[0], t[1]);
            assertThat(((BusinessException) t[1]).getErrorDetails()).containsEntry("remainingAttempts", 3L);
        });
    }

    @Test
    void theTenthLocksNobodyToo() {
        theNthWrongPassword(10, t -> {
            sameAnswer(t[0], t[1]);
            assertThat(((BusinessException) t[1]).getErrorCode()).isEqualTo("PASSWORD_LOCKED");
        });
    }

    @Test
    void whileLocked_nobodyIsRefusedWithoutBeingCounted_likeARealPerson() {
        // Counting while locked would end the made-up lock at a different moment from the real one.
        UserIdentity alice = somebodyHolds("alice@acme.com");
        when(identityService.isPasswordLocked(alice)).thenReturn(true);
        when(identityService.isUnknownIdentifierLocked(anyString())).thenReturn(true);

        Throwable known = catchThrowable(() -> loginService.authenticateByPassword("alice@acme.com", "x"));
        Throwable unknown = catchThrowable(() -> loginService.authenticateByPassword("nobody@acme.com", "x"));

        sameAnswer(known, unknown);
        verify(identityService, never()).recordUnknownIdentifierFailure(anyString());
        verify(identityService, never()).recordPasswordFailure(any());
    }

    // ───────────────────────── a code: signing in, resetting ─────────────────────────

    private static BusinessException wrongCode() {
        return io.softa.starter.user.constant.AccountErrorCodes.refusal(
                io.softa.starter.user.constant.AccountErrorCodes.CODE_INCORRECT, "Incorrect code. Please try again.");
    }

    @Test
    void signingInWithACode_nobodysNumberGetsTheWrongCodeAnswer() {
        // Code first, person second; and a number that is nobody's — even one whose code checks
        // out — is told exactly what a wrong code is told.
        UserIdentity alice = somebodyHolds("+6591234567");
        doThrow(wrongCode()).when(codeGuard).verify("+6591234567", "000000");
        Throwable known = catchThrowable(() -> loginService.authenticateByCode("+6591234567", "000000"));

        Throwable unknown = catchThrowable(() -> loginService.authenticateByCode("+6590000001", "123456"));

        sameAnswer(known, unknown);
        verify(codeGuard).verify("+6590000001", "123456");
        assertThat(alice).isNotNull();
    }

    @Test
    void resettingWithACode_nobodysNumberGetsTheWrongCodeAnswer_andNothingIsWritten() {
        somebodyHolds("+6591234567");
        doThrow(wrongCode()).when(codeGuard).verify("+6591234567", "000000");
        Throwable known = catchThrowable(
                () -> loginService.resetPasswordByCode("+6591234567", "000000", "N3w-Passw0rd!"));

        Throwable unknown = catchThrowable(
                () -> loginService.resetPasswordByCode("+6590000001", "123456", "N3w-Passw0rd!"));

        sameAnswer(known, unknown);
        verify(codeGuard).verify("+6590000001", "123456");
        verify(identityService, never()).setPassword(any(Long.class), anyString());
    }
}
