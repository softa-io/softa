package io.softa.starter.user.service.impl;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.exception.BusinessException;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.enums.AccountStatus;
import io.softa.starter.user.service.ConsultantService;
import io.softa.starter.user.service.UserAccountService;
import io.softa.starter.user.service.UserIdentityService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The two places login treats a consultant differently (PRD C1 and CE2).
 *
 * <p>Both are about not sending someone down a road that does not apply to them: an employee is
 * forced to set a password because without one they cannot return through the password route, while
 * a consultant was created with no invitation and code login is their intended way in. And a
 * consultant with no live grant is not an employee with no company — the account is fine, an
 * authorization ended, and only the platform can extend it.
 */
class ConsultantLoginRulesTest {

    private static final Long PROFILE = 7L;

    private final UserAccountService accountService = mock(UserAccountService.class);
    private final UserIdentityService identityService = mock(UserIdentityService.class);
    private final ConsultantService consultantService = mock(ConsultantService.class);
    private final LoginServiceImpl loginService = new LoginServiceImpl();

    ConsultantLoginRulesTest() {
        ReflectionTestUtils.setField(loginService, "accountService", accountService);
        ReflectionTestUtils.setField(loginService, "identityService", identityService);
        ReflectionTestUtils.setField(loginService, "consultantService", consultantService);
    }

    private static UserAccount membership(boolean consultant, AccountStatus status) {
        UserAccount account = new UserAccount();
        account.setId(1L);
        account.setTenantId(100L);
        account.setProfileId(PROFILE);
        account.setStatus(status);
        account.setConsultant(consultant);
        return account;
    }

    @Test
    void aConsultantIsNeverForcedToSetAPassword() {
        when(consultantService.isConsultant(PROFILE)).thenReturn(true);

        // Not even asked about the credential: the exemption is about not FORCING the step, and a
        // consultant with no password is the normal, intended state.
        assertThat(loginService.mustSetPassword(PROFILE)).isFalse();
    }

    @Test
    void anEmployeeWithNoPasswordIsStillForced() {
        when(consultantService.isConsultant(PROFILE)).thenReturn(false);
        io.softa.starter.user.entity.UserIdentity identity = new io.softa.starter.user.entity.UserIdentity();
        identity.setProfileId(PROFILE);
        identity.setPassword(null);
        when(identityService.findByProfile(PROFILE)).thenReturn(java.util.Optional.of(identity));

        assertThat(loginService.mustSetPassword(PROFILE)).isTrue();
    }

    @Test
    void aConsultantWithNoLiveGrantIsSentToThePlatform_notToATenantAdmin() {
        when(consultantService.isConsultant(PROFILE)).thenReturn(true);
        when(accountService.listMembershipsOf(PROFILE))
                .thenReturn(List.of(membership(true, AccountStatus.ACTIVE)));

        BusinessException refusal = (BusinessException) ReflectionTestUtils
                .invokeMethod(loginService, "noCompanyRefusal", PROFILE);

        assertThat(refusal.getMessage()).contains("platform administrator");
    }

    @Test
    void someoneWhoIsBothGetsTheEmployeeWording_theHalfTheyCanActOn() {
        // A consultant who is ALSO an employee somewhere: telling them to contact the platform
        // would be wrong about the employment, which is the part a tenant administrator can fix.
        when(consultantService.isConsultant(PROFILE)).thenReturn(true);
        when(accountService.listMembershipsOf(PROFILE)).thenReturn(List.of(
                membership(true, AccountStatus.ACTIVE),
                membership(false, AccountStatus.INVITED)));

        BusinessException refusal = (BusinessException) ReflectionTestUtils
                .invokeMethod(loginService, "noCompanyRefusal", PROFILE);

        assertThat(refusal.getMessage()).doesNotContain("platform administrator");
    }
}
