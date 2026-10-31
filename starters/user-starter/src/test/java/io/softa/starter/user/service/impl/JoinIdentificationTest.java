package io.softa.starter.user.service.impl;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.context.UserInfo;
import io.softa.starter.user.dto.AuthenticationResult;
import io.softa.starter.user.dto.JoinContacts;
import io.softa.starter.user.dto.JoinVerification;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.entity.UserIdentity;
import io.softa.starter.user.enums.AccountStatus;
import io.softa.starter.user.service.ConsultantService;
import io.softa.starter.user.service.UserAccountService;
import io.softa.starter.user.service.UserIdentityService;
import io.softa.starter.user.service.UserInvitationService;
import io.softa.starter.user.service.UserProfileService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Who /join decides is joining: by the account's current personal mobile, with a second check
 * when only the email was verified.
 */
class JoinIdentificationTest {

    private static final String TOKEN = "raw-token";
    private static final String EMAIL = "ada@acme.com";
    private static final String MOBILE = "+6591234567";
    private static final Long ACCOUNT = 100L;
    private static final Long PLACEHOLDER = 5L;
    private static final Long EXISTING = 7L;
    private static final Long NEW_PERSON = 9L;

    private final UserInvitationService invitationService = mock(UserInvitationService.class);
    private final UserAccountService accountService = mock(UserAccountService.class);
    private final UserIdentityService identityService = mock(UserIdentityService.class);
    private final UserProfileService profileService = mock(UserProfileService.class);
    private final JoinProofGuard proofGuard = mock(JoinProofGuard.class);
    private final LoginServiceImpl loginService = new LoginServiceImpl();
    private final UserAccount account = new UserAccount();

    JoinIdentificationTest() {
        ReflectionTestUtils.setField(loginService, "invitationService", invitationService);
        ReflectionTestUtils.setField(loginService, "accountService", accountService);
        ReflectionTestUtils.setField(loginService, "identityService", identityService);
        ReflectionTestUtils.setField(loginService, "profileService", profileService);
        ReflectionTestUtils.setField(loginService, "proofGuard", proofGuard);
        ReflectionTestUtils.setField(loginService, "codeGuard", mock(VerificationCodeGuard.class));
        ReflectionTestUtils.setField(loginService, "consultantService", mock(ConsultantService.class));
        ReflectionTestUtils.setField(loginService, "history", mock(LoginHistoryRecorder.class));
        ReflectionTestUtils.setField(loginService, "mobileFormat", InvitationServiceTestSupport.mobileFormat());

        account.setId(ACCOUNT);
        account.setTenantId(1L);
        account.setProfileId(PLACEHOLDER);
        account.setStatus(AccountStatus.INVITED);
        account.setEmail(EMAIL);
        account.setMobile(MOBILE);
        when(invitationService.resolveJoinAccount(TOKEN)).thenReturn(Optional.of(account));
        when(invitationService.resolveJoinChannel(TOKEN, "email")).thenReturn(EMAIL);
        when(invitationService.resolveJoinChannel(TOKEN, "mobile")).thenReturn(MOBILE);
        when(invitationService.resolveJoinContacts(TOKEN)).thenReturn(new JoinContacts(EMAIL, MOBILE));
        when(proofGuard.mint(any(), any(), any())).thenReturn("proof");
        when(profileService.createPersonForJoin(MOBILE)).thenReturn(NEW_PERSON);
        when(identityService.findByLoginIdentifier(MOBILE)).thenReturn(Optional.empty());
    }

    private UserIdentity holding(Long profileId, String password) {
        UserIdentity identity = new UserIdentity();
        identity.setProfileId(profileId);
        identity.setLoginMobile(MOBILE);
        identity.setPassword(password);
        when(identityService.findByLoginIdentifier(MOBILE)).thenReturn(Optional.of(identity));
        when(identityService.findByProfile(profileId)).thenReturn(Optional.of(identity));
        return identity;
    }

    @Test
    void byEmail_whenThePersonalMobileIsSomeoneOnFile_theMobileMustBeVerifiedToo() {
        holding(EXISTING, "hash");

        JoinVerification result = loginService.verifyJoinCode(TOKEN, "email", "123456");

        assertThat(result.nextStep()).isEqualTo(JoinVerification.NextStep.VERIFY_MOBILE);
        assertThat(result.proof()).isNull();
        assertThat(result.maskedMobile()).isEqualTo("+65 912****67");
        verify(proofGuard, never()).mint(any(), any(), any());
    }

    @Test
    void byMobile_theHolderIsThePerson_andConfirmsDirectlyWhenTheyHaveAPassword() {
        holding(EXISTING, "hash");

        JoinVerification result = loginService.verifyJoinCode(TOKEN, "mobile", "123456");

        assertThat(result.profileId()).isEqualTo(EXISTING);
        assertThat(result.nextStep()).isEqualTo(JoinVerification.NextStep.CONFIRM);
        verify(proofGuard).mint(TOKEN, EXISTING, "mobile");
    }

    @Test
    void byEmail_theAccountsOwnPlaceholderPerson_needsNoSecondCheck() {
        // Created with the account and nothing else: there is nobody to impersonate.
        holding(PLACEHOLDER, null);
        when(invitationService.isPlaceholderPerson(PLACEHOLDER, ACCOUNT)).thenReturn(true);

        JoinVerification result = loginService.verifyJoinCode(TOKEN, "email", "123456");

        assertThat(result.profileId()).isEqualTo(PLACEHOLDER);
        assertThat(result.nextStep()).isEqualTo(JoinVerification.NextStep.SET_PASSWORD);
    }

    @Test
    void aDormantHolder_isReleased_andANewPersonIsMade() {
        holding(EXISTING, "hash");
        when(accountService.releaseDormantLoginMobile(MOBILE, "Join")).thenReturn(true);

        JoinVerification result = loginService.verifyJoinCode(TOKEN, "email", "123456");

        assertThat(result.profileId()).isEqualTo(NEW_PERSON);
        assertThat(result.nextStep()).isEqualTo(JoinVerification.NextStep.SET_PASSWORD);
        verify(proofGuard).rememberJoinPerson(TOKEN, NEW_PERSON);
    }

    @Test
    void verifyingTheSameLinkAgain_findsThePersonItMade_insteadOfReleasingThemAndMakingAnother() {
        // The person made on the first try holds no account until confirmJoin, which is exactly what
        // a dormant holder looks like. Releasing their number would orphan them and make a third.
        holding(NEW_PERSON, null);
        when(proofGuard.joinPersonOf(TOKEN)).thenReturn(NEW_PERSON);

        JoinVerification result = loginService.verifyJoinCode(TOKEN, "email", "123456");

        assertThat(result.profileId()).isEqualTo(NEW_PERSON);
        assertThat(result.nextStep()).isEqualTo(JoinVerification.NextStep.SET_PASSWORD);
        verify(accountService, never()).releaseDormantLoginMobile(anyString(), anyString());
        verify(profileService, never()).createPersonForJoin(anyString());
    }

    @Test
    void aReHire_isRecognisedAsThePersonTheyWere_byTheirOwnNumber() {
        // Left every company, so on paper dormant — but this reopened row remembers them, and the
        // number verified is theirs: the same person comes back with their password and settings.
        account.setProfileId(null);
        account.setFormerProfileId(EXISTING);
        holding(EXISTING, "hash");

        JoinVerification result = loginService.verifyJoinCode(TOKEN, "mobile", "123456");

        assertThat(result.profileId()).isEqualTo(EXISTING);
        assertThat(result.nextStep()).isEqualTo(JoinVerification.NextStep.CONFIRM);
        verify(accountService, never()).releaseDormantLoginMobile(anyString(), anyString());
    }

    @Test
    void aReHireVerifiedByEmailOnly_stillProvesTheMobile() {
        // The mailbox proves the employee, not that the remembered person is them.
        account.setProfileId(null);
        account.setFormerProfileId(EXISTING);
        holding(EXISTING, "hash");

        JoinVerification result = loginService.verifyJoinCode(TOKEN, "email", "123456");

        assertThat(result.nextStep()).isEqualTo(JoinVerification.NextStep.VERIFY_MOBILE);
    }

    @Test
    void confirming_entersTheJoinedCompany_andPrefillsTheVerifiedContact() {
        holding(EXISTING, "hash");
        when(proofGuard.channelOf("proof")).thenReturn("mobile");
        UserInfo joined = new UserInfo();
        joined.setUserId(ACCOUNT);
        when(profileService.getUserInfo(ACCOUNT)).thenReturn(joined);
        // Several other companies, and still straight into this one.
        when(accountService.listMembershipsOf(EXISTING)).thenReturn(List.of(new UserAccount(), new UserAccount()));

        AuthenticationResult result = loginService.confirmJoin(TOKEN, EXISTING, "proof");

        verify(invitationService).confirmJoin(TOKEN, EXISTING, "proof");
        assertThat(result.isResolved()).isTrue();
        assertThat(result.autoEnterAccountId()).isEqualTo(ACCOUNT);
        assertThat(result.signInRequired()).isFalse();
        assertThat(result.prefill()).isEqualTo(new AuthenticationResult.JoinPrefill("mobile", MOBILE));
    }

    @Test
    void theFormatter_splitsDialCodeAndCountry() {
        JoinMobileFormat.Formatted formatted = InvitationServiceTestSupport.mobileFormat().format("+86 138-0013-8000");

        assertThat(formatted.dialCode()).isEqualTo("+86");
        assertThat(formatted.countryName()).isEqualTo("China");
        assertThat(formatted.masked()).isEqualTo("+86 138****00");
        verify(accountService, never()).isEmailHeldByAnotherPerson(eq(EMAIL), any(), any());
    }
}
