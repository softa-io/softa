package io.softa.starter.user.service.impl;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.entity.UserIdentity;
import io.softa.starter.user.service.UserAccountService;
import io.softa.starter.user.service.UserIdentityService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import io.softa.starter.user.service.ConsultantService;

/**
 * Whether the forced Set Password step survives a page reload.
 *
 * <p>Authentication reports {@code mustSetPassword} once, in its response. A client that only
 * remembers it from there loses the requirement on refresh — and since a password-less person
 * cannot come back through the password route, that does not postpone a screen, it locks them out
 * of their own account. This is the session-based answer that makes the step recoverable.
 */
class MustSetMyPasswordTest {

    private static final Long USER = 100L;
    private static final Long PROFILE = 7L;

    private final UserIdentityService identityService = mock(UserIdentityService.class);
    private final ConsultantService consultantService =
            mock(ConsultantService.class);
    private final UserAccountServiceImpl accountService = spy(new UserAccountServiceImpl());
    /** The memberships the exemption is decided from — see {@code holdsOnlyConsultantMemberships}. */
    private final UserAccountService memberships = mock(UserAccountService.class);

    /**
     * A REAL LoginServiceImpl over the mocked credential store, not a stubbed rule.
     *
     * <p>The endpoint delegates now — whether a password is owed has one definition, because the
     * login page ORs this answer with the login response and two definitions meant the stricter,
     * wrong one always won. Stubbing the delegate would make these cases assert the stub; wiring the
     * real rule keeps them asserting the behaviour they were written for.
     */
    MustSetMyPasswordTest() {
        LoginServiceImpl loginService = new LoginServiceImpl();
        ReflectionTestUtils.setField(loginService, "identityService", identityService);
        ReflectionTestUtils.setField(loginService, "consultantService", consultantService);
        ReflectionTestUtils.setField(loginService, "accountService", memberships);
        ReflectionTestUtils.setField(accountService, "identityService", identityService);
        ReflectionTestUtils.setField(accountService, "loginService", loginService);
    }

    /** ContextHolder is a ScopedValue — a session exists only inside runWith/callWith. */
    private boolean asUser(Long userId) {
        Context context = new Context();
        context.setUserId(userId);
        return ContextHolder.callWith(context, accountService::mustSetMyPassword);
    }

    /** One membership of the given kind, for the exemption's "every membership" question. */
    private static UserAccount membership(boolean consultant) {
        UserAccount account = new UserAccount();
        account.setId(USER);
        account.setProfileId(PROFILE);
        account.setConsultant(consultant);
        return account;
    }

    private static UserAccount account(Long profileId) {
        UserAccount account = new UserAccount();
        account.setId(USER);
        account.setProfileId(profileId);
        return account;
    }

    private static UserIdentity identity(String password) {
        UserIdentity identity = new UserIdentity();
        identity.setId(11L);
        identity.setProfileId(PROFILE);
        identity.setPassword(password);
        return identity;
    }

    @Test
    void aPersonWithNoPassword_stillOwesOne() {
        doReturn(Optional.of(account(PROFILE))).when(accountService).getById(USER);
        when(identityService.findByProfile(PROFILE)).thenReturn(Optional.of(identity(null)));

        assertThat(asUser(USER)).isTrue();
    }

    @Test
    void aPersonWhoHasOne_owesNothing() {
        doReturn(Optional.of(account(PROFILE))).when(accountService).getById(USER);
        when(identityService.findByProfile(PROFILE)).thenReturn(Optional.of(identity("hash")));

        assertThat(asUser(USER)).isFalse();
    }

    @Test
    void noCredentialsRowAtAll_doesNotForceAScreenThatWouldBeRefused() {
        // A data fault, not a password-less person. Forcing the step here would put someone in
        // front of a form whose own endpoint (requireIdentity) throws.
        doReturn(Optional.of(account(PROFILE))).when(accountService).getById(USER);
        when(identityService.findByProfile(PROFILE)).thenReturn(Optional.empty());

        assertThat(asUser(USER)).isFalse();
    }

    @Test
    void noSession_owesNothing() {
        assertThat(asUser(null)).isFalse();
    }

    @Test
    void aConsultantOwesNothingEvenWithNoPassword() {
        // The case that made this endpoint matter: a consultant cleared by the login
        // response still met the wall here, because this ran its own blank-password test and the
        // login page ORs the two.
        doReturn(Optional.of(account(PROFILE))).when(accountService).getById(USER);
        when(identityService.findByProfile(PROFILE)).thenReturn(Optional.of(identity(null)));
        when(consultantService.isConsultant(PROFILE)).thenReturn(true);
        when(memberships.listMembershipsOf(PROFILE)).thenReturn(List.of(membership(true)));

        assertThat(asUser(USER)).isFalse();
    }

    @Test
    void aConsultantWhoIsALSOAnEmployee_stillOwesOne() {
        // The exemption is about consultancy, but it was asked of the PERSON: one call to
        // isConsultant and anybody who consults anywhere was excused. Somebody can be an employee at
        // one company and a consultant for another — the design says so — and the employee half is
        // exactly the half that cannot come back without a password. So a dual-hat person keeps the
        // forced step; only somebody whose every membership is a consultancy is excused.
        doReturn(Optional.of(account(PROFILE))).when(accountService).getById(USER);
        when(identityService.findByProfile(PROFILE)).thenReturn(Optional.of(identity(null)));
        when(consultantService.isConsultant(PROFILE)).thenReturn(true);
        when(memberships.listMembershipsOf(PROFILE))
                .thenReturn(List.of(membership(true), membership(false)));

        assertThat(asUser(USER)).isTrue();
    }
}
