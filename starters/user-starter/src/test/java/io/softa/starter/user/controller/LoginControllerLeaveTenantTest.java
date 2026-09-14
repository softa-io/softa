package io.softa.starter.user.controller;

import java.util.List;
import java.util.Optional;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.service.CacheService;
import io.softa.framework.orm.service.TenantInfoService;
import io.softa.starter.user.dto.AuthenticationResult;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.enums.AccountStatus;
import io.softa.starter.user.service.ConsultantService;
import io.softa.starter.user.service.UserAccountService;
import io.softa.starter.user.service.UserIdentityService;
import io.softa.starter.user.service.UserProfileService;
import io.softa.starter.user.service.impl.LoginServiceImpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CE3's exit: a consultant whose authorization for the current tenant ended goes back to the
 * company step — authenticated, with their other companies on offer — rather than out.
 *
 * <p>The first client did the only thing the API allowed: it signed the person out, and made them
 * prove who they are again with a fresh code to reach a picker the server already knew the contents
 * of. This endpoint answers the picker's own shape, so the client can hand the person straight to
 * it. Driven through the real controller and the real {@link LoginServiceImpl}, because the seam is
 * the point: the controller owns the session and the cookie, the service owns who may go where.
 *
 * <p>Three things are pinned. The answer is CHOICE-PENDING — a pre-auth token and the memberships,
 * never a session (one route to a session, not two). The old session is dropped and the cookie
 * cleared, or the login page would probe a dead id and read the 401 as a sign-out. And when nothing
 * is left to enter, the refusal is CE2's wording, raised BEFORE the session is touched, so a
 * consultant with nowhere to go is not also logged out mid-request.
 */
class LoginControllerLeaveTenantTest {

    private static final Long PROFILE = 7L;
    private static final Long HERE = 100L;
    private static final Long THERE = 200L;
    private static final String SESSION = "session-in-here";

    private final UserAccountService accountService = mock(UserAccountService.class);
    private final UserProfileService profileService = mock(UserProfileService.class);
    private final UserIdentityService identityService = mock(UserIdentityService.class);
    private final TenantInfoService tenantInfoService = mock(TenantInfoService.class);
    private final ConsultantService consultantService = mock(ConsultantService.class);
    private final CacheService cacheService = mock(CacheService.class);
    private final LoginServiceImpl loginService = new LoginServiceImpl();
    private final LoginController controller = new LoginController();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    private SystemConfig previousEnv;

    @BeforeEach
    void setUp() {
        previousEnv = SystemConfig.env;
        SystemConfig env = new SystemConfig();
        env.setEnableMultiTenancy(false);
        SystemConfig.env = env;

        ReflectionTestUtils.setField(loginService, "accountService", accountService);
        ReflectionTestUtils.setField(loginService, "consultantService", consultantService);
        ReflectionTestUtils.setField(loginService, "profileService", profileService);
        ReflectionTestUtils.setField(loginService, "identityService", identityService);
        ReflectionTestUtils.setField(loginService, "tenantInfoService", tenantInfoService);
        ReflectionTestUtils.setField(loginService, "cacheService", cacheService);
        ReflectionTestUtils.setField(controller, "loginService", loginService);
        ReflectionTestUtils.setField(controller, "cacheService", cacheService);

        when(tenantInfoService.getTenantName(anyLong())).thenReturn("Some Co");
        when(tenantInfoService.isTenantActive(anyLong())).thenReturn(true);
        when(identityService.findByProfile(PROFILE)).thenReturn(Optional.empty());
    }

    @AfterEach
    void restoreEnv() {
        SystemConfig.env = previousEnv;
    }

    private static UserAccount membership(Long accountId, Long tenantId, boolean consultant) {
        UserAccount account = new UserAccount();
        account.setId(accountId);
        account.setTenantId(tenantId);
        account.setProfileId(PROFILE);
        account.setStatus(AccountStatus.ACTIVE);
        account.setConsultant(consultant);
        return account;
    }

    private MockHttpServletRequest signedInAt(Long accountId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(BaseConstant.SESSION_ID, SESSION));
        when(cacheService.get("session:" + SESSION, Long.class)).thenReturn(accountId);
        return request;
    }

    private void givenMemberships(UserAccount... accounts) {
        when(accountService.listMembershipsOf(PROFILE)).thenReturn(List.of(accounts));
        for (UserAccount account : accounts) {
            when(accountService.getById(account.getId())).thenReturn(Optional.of(account));
        }
    }

    @Test
    void leavingHandsBackThePickerNotASession_andDropsTheOldSession() {
        // HERE is the consultancy that just ended (no live grant → absent from the picker by
        // construction); THERE is an employment that still stands.
        givenMemberships(membership(HERE, 1L, true), membership(THERE, 2L, false));
        when(consultantService.grantStands(PROFILE, 1L)).thenReturn(false);
        MockHttpServletRequest request = signedInAt(HERE);

        AuthenticationResult result = controller.leaveTenant(request, response).getData();

        // Choice pending: a token to spend on selectTenant, no session issued here.
        assertThat(result.userInfo()).isNull();
        assertThat(result.authToken()).isNotBlank();
        assertThat(result.tenants()).extracting("accountId").containsExactly(THERE);
        verify(cacheService, never()).save(startsWithSession(), any(), anyInt());
        // The session the person was inside is gone, on the server and in the browser.
        verify(cacheService).clear("session:" + SESSION);
        Cookie cleared = response.getCookie(BaseConstant.SESSION_ID);
        assertThat(cleared).isNotNull();
        assertThat(cleared.getMaxAge()).isZero();
    }

    /** Matches the session-key namespace and nothing else — pre-auth tokens are saved under their own. */
    private static String startsWithSession() {
        return org.mockito.ArgumentMatchers.startsWith("session:");
    }

    @Test
    void aConsultantWithNothingLeftIsToldSo_andKeepsTheSessionTheyHad() {
        // CE2. Every grant lapsed: the picker would be empty, so the refusal says who can fix it —
        // the platform, not "your administrator". Raised BEFORE the session is dropped: a refusal
        // must leave the caller exactly where they were, even if "where they were" is a tenant that
        // will refuse their next request too.
        givenMemberships(membership(HERE, 1L, true));
        when(consultantService.grantStands(PROFILE, 1L)).thenReturn(false);
        when(consultantService.isConsultant(PROFILE)).thenReturn(true);
        MockHttpServletRequest request = signedInAt(HERE);

        assertThatThrownBy(() -> controller.leaveTenant(request, response))
                .isInstanceOf(BusinessException.class)
                .hasMessage("No accessible tenant. Please contact the platform administrator.");

        verify(cacheService, never()).clear(anyString());
        assertThat(response.getCookie(BaseConstant.SESSION_ID)).isNull();
    }

    @Test
    void anUnknownSessionIsRefusedBeforeAnythingIsRead() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(BaseConstant.SESSION_ID, "forged"));
        when(cacheService.get(eq("session:forged"), eq(Long.class))).thenReturn(null);

        assertThatThrownBy(() -> controller.leaveTenant(request, response))
                .hasMessageContaining("Invalid session ID");
        verify(accountService, never()).listMembershipsOf(any());
    }
}
