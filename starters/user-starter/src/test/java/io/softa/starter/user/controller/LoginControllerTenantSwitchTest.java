package io.softa.starter.user.controller;

import java.util.List;
import java.util.Optional;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.context.UserInfo;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.exception.UserNotFoundException;
import io.softa.framework.orm.service.CacheService;
import io.softa.framework.orm.service.TenantInfoService;
import io.softa.starter.user.dto.AuthenticationResult;
import io.softa.starter.user.dto.MembershipOption;
import io.softa.starter.user.dto.SwitchTenantDTO;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.enums.AccountStatus;
import io.softa.starter.user.service.UserAccountService;
import io.softa.starter.user.service.UserIdentityService;
import io.softa.starter.user.service.UserProfileService;
import io.softa.starter.user.service.impl.LoginServiceImpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The header's tenant switcher: moving an ALREADY signed-in session from one of a person's
 * tenants to another.
 *
 * <p>Driven through the real controller and the real {@link LoginServiceImpl}, because the whole
 * point of this endpoint sits in the seam between them — the controller decides what authorizes the
 * call and which session id survives it, the service decides which company the caller may name.
 *
 * <p>Two assertions here are the load-bearing ones. {@link #switchingCompanies_issuesASessionForTheTargetAndDropsTheOldOne()}
 * pins that the new session maps to the TARGET account and that the previous session key is cleared
 * — without the clear, a copied cookie keeps the previous company alive after the switch. And
 * {@link #namingSomebodyElsesMembership_isRefusedAndMintsNothing()} pins that the authorization is
 * the caller's own membership list: without it, any signed-in person could name any accountId and
 * be handed a session inside a company they do not belong to.
 */
class LoginControllerTenantSwitchTest {

    private static final Long PROFILE = 7L;
    private static final Long HERE = 100L;
    private static final Long THERE = 200L;
    private static final String OLD_SESSION = "old-session-id";

    private final UserAccountService accountService = mock(UserAccountService.class);
    private final UserProfileService profileService = mock(UserProfileService.class);
    private final UserIdentityService identityService = mock(UserIdentityService.class);
    private final TenantInfoService tenantInfoService = mock(TenantInfoService.class);
    private final CacheService cacheService = mock(CacheService.class);
    private final LoginServiceImpl loginService = new LoginServiceImpl();
    private final LoginController controller = new LoginController();

    private final MockHttpServletResponse response = new MockHttpServletResponse();

    /** {@code SystemConfig.env} is a @PostConstruct singleton; a plain unit test has to supply it. */
    private SystemConfig previousEnv;

    @BeforeEach
    void setUp() {
        previousEnv = SystemConfig.env;
        SystemConfig env = new SystemConfig();
        // Single-tenant: the tenant gate then short-circuits, leaving the ACCOUNT gate — the one
        // this endpoint has to re-run against the company being entered — as the thing under test.
        env.setEnableMultiTenancy(false);
        SystemConfig.env = env;

        ReflectionTestUtils.setField(loginService, "accountService", accountService);
        ReflectionTestUtils.setField(loginService, "consultantService",
                mock(io.softa.starter.user.service.ConsultantService.class));
        ReflectionTestUtils.setField(loginService, "profileService", profileService);
        ReflectionTestUtils.setField(loginService, "identityService", identityService);
        ReflectionTestUtils.setField(loginService, "tenantInfoService", tenantInfoService);
        ReflectionTestUtils.setField(loginService, "cacheService", cacheService);
        ReflectionTestUtils.setField(controller, "loginService", loginService);
        ReflectionTestUtils.setField(controller, "cacheService", cacheService);

        when(tenantInfoService.getTenantName(1L)).thenReturn("Acme");
        when(tenantInfoService.getTenantName(2L)).thenReturn("Globex");
        when(identityService.findByProfile(PROFILE)).thenReturn(Optional.empty());
    }

    @AfterEach
    void restoreEnv() {
        SystemConfig.env = previousEnv;   // a static — do not leak it into the next test class
    }

    private static UserAccount membership(Long accountId, Long tenantId, AccountStatus status) {
        UserAccount account = new UserAccount();
        account.setId(accountId);
        account.setTenantId(tenantId);
        account.setProfileId(PROFILE);
        account.setStatus(status);
        return account;
    }

    /** A live session cookie standing for the membership the caller is currently inside. */
    private MockHttpServletRequest signedInAt(Long accountId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(BaseConstant.SESSION_ID, OLD_SESSION));
        when(cacheService.get("session:" + OLD_SESSION, Long.class)).thenReturn(accountId);
        return request;
    }

    private void givenMemberships(UserAccount... accounts) {
        when(accountService.listMembershipsOf(PROFILE)).thenReturn(List.of(accounts));
        for (UserAccount account : accounts) {
            when(accountService.getById(account.getId())).thenReturn(Optional.of(account));
        }
    }

    private static SwitchTenantDTO switchTo(Long accountId) {
        SwitchTenantDTO dto = new SwitchTenantDTO();
        dto.setAccountId(accountId);
        return dto;
    }

    private static UserInfo userInfoOf(Long accountId) {
        UserInfo userInfo = new UserInfo();
        userInfo.setUserId(accountId);
        return userInfo;
    }

    @Test
    void switchingCompanies_issuesASessionForTheTargetAndDropsTheOldOne() {
        givenMemberships(membership(HERE, 1L, AccountStatus.ACTIVE),
                membership(THERE, 2L, AccountStatus.ACTIVE));
        when(profileService.getUserInfo(THERE)).thenReturn(userInfoOf(THERE));
        MockHttpServletRequest request = signedInAt(HERE);

        AuthenticationResult result =
                controller.switchTenant(switchTo(THERE), request, response).getData();

        assertThat(result.isResolved()).isTrue();
        assertThat(result.userInfo().getUserId()).isEqualTo(THERE);

        // Load-bearing: the session that comes out maps to the TARGET membership, not the one the
        // caller was already in — the session id is what every downstream layer keys its tenant,
        // permission and scope caches off.
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> value = ArgumentCaptor.forClass(Object.class);
        verify(cacheService).save(key.capture(), value.capture(), anyInt());
        assertThat(value.getValue()).isEqualTo(THERE);
        String newSessionId = key.getValue().substring("session:".length());
        assertThat(newSessionId).isNotEqualTo(OLD_SESSION);

        // Load-bearing: the previous session id is invalidated. Left standing, a copy of the old
        // cookie keeps the previous company open next to the new one.
        verify(cacheService).clear("session:" + OLD_SESSION);

        assertThat(response.getHeader("Set-Cookie")).contains(BaseConstant.SESSION_ID + "=" + newSessionId);
    }

    @Test
    void namingSomebodyElsesMembership_isRefusedAndMintsNothing() {
        // The caller belongs to one company; 999 is somebody else's membership.
        givenMemberships(membership(HERE, 1L, AccountStatus.ACTIVE));
        MockHttpServletRequest request = signedInAt(HERE);

        assertThatThrownBy(() -> controller.switchTenant(switchTo(999L), request, response))
                .isInstanceOf(BusinessException.class)
                .hasMessage("That company is not available for your account.");

        // Load-bearing: nothing is issued and the caller's own session is untouched, so a refused
        // switch cannot become either a foothold in another company or a self-inflicted logout.
        verify(cacheService, never()).save(anyString(), any(), anyInt());
        verify(cacheService, never()).clear(anyString());
    }

    @Test
    void aFrozenMembership_isRefusedWithItsOwnStatusMessage() {
        givenMemberships(membership(HERE, 1L, AccountStatus.ACTIVE),
                membership(THERE, 2L, AccountStatus.FROZEN));
        MockHttpServletRequest request = signedInAt(HERE);

        // The picker lists a frozen company so the person can see it exists; entering it is refused
        // with the reason, exactly as the login-time company step refuses it.
        assertThatThrownBy(() -> controller.switchTenant(switchTo(THERE), request, response))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Login denied: your account has been deactivated.");
        verify(cacheService, never()).save(anyString(), any(), anyInt());
    }

    /**
     * The refusals below come from {@code generateSessionId}, not from the membership check — the
     * target company was the caller's own and selectable, and it is the TENANT / ACCOUNT gate run
     * against it that says no. What they pin is the ORDERING the controller already has: both gates
     * run before {@code cacheService.clear} touches the previous session. Reversed — clear first,
     * mint second — a refused switch would log the caller out of the company they were sitting in,
     * turning "you may not enter that one" into "you are now in none of them".
     */
    @Test
    void aTargetTenantThatFailsItsGate_isRefused_andTheCallerKeepsTheSessionTheyHave() {
        SystemConfig.env.setEnableMultiTenancy(true);   // the tenant gate is a no-op without it
        givenMemberships(membership(HERE, 1L, AccountStatus.ACTIVE),
                membership(THERE, 2L, AccountStatus.ACTIVE));
        // validateTenantActive reads the tenant off the TARGET membership's UserInfo.
        UserInfo there = userInfoOf(THERE);
        there.setTenantId(2L);
        when(profileService.getUserInfo(THERE)).thenReturn(there);
        when(tenantInfoService.isTenantProvisioned(2L)).thenReturn(true);
        // The company the caller is in is fine; it is the one they are trying to enter that is not.
        when(tenantInfoService.isTenantActive(2L)).thenReturn(false);
        MockHttpServletRequest request = signedInAt(HERE);

        assertThatThrownBy(() -> controller.switchTenant(switchTo(THERE), request, response))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Login denied: tenant is not active.");

        // Load-bearing: nothing minted, and — the half this ordering exists for — the caller's own
        // session key is never cleared, so they are still inside the company they started in.
        verify(cacheService, never()).save(anyString(), any(), anyInt());
        verify(cacheService, never()).clear(startsWith("session:"));
    }

    @Test
    void aTargetAccountThatFailsItsGate_isRefused_andTheCallerKeepsTheSessionTheyHave() {
        // Provoked independently of the membership check: the listing says the target is ACTIVE and
        // selectable, and the account gate — which re-reads the row at session-issue time — finds it
        // frozen. That is the real race the second gate is there for (an admin freezing the
        // membership between the switcher rendering and the switch being clicked), and it is the
        // only way into validateAccountActive once the selectable() check has passed.
        givenMemberships(membership(HERE, 1L, AccountStatus.ACTIVE),
                membership(THERE, 2L, AccountStatus.ACTIVE));
        when(accountService.getById(THERE))
                .thenReturn(Optional.of(membership(THERE, 2L, AccountStatus.FROZEN)));
        when(profileService.getUserInfo(THERE)).thenReturn(userInfoOf(THERE));
        MockHttpServletRequest request = signedInAt(HERE);

        assertThatThrownBy(() -> controller.switchTenant(switchTo(THERE), request, response))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Login denied: your account has been deactivated.");

        verify(cacheService, never()).save(anyString(), any(), anyInt());
        verify(cacheService, never()).clear(startsWith("session:"));
    }

    @Test
    void noSessionAtAll_isRefused() {
        // /login/** is anonymous at the context filter and public in the permission gate, so an
        // unauthenticated caller really does reach this handler. Nothing upstream refused it.
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThatThrownBy(() -> controller.switchTenant(switchTo(THERE), request, response))
                .isInstanceOf(UserNotFoundException.class);
        assertThatThrownBy(() -> controller.myTenants(request))
                .isInstanceOf(UserNotFoundException.class);
        verify(accountService, never()).listMembershipsOf(any());
    }

    @Test
    void anExpiredSession_isRefused() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(BaseConstant.SESSION_ID, OLD_SESSION));
        when(cacheService.get("session:" + OLD_SESSION, Long.class)).thenReturn(null);

        assertThatThrownBy(() -> controller.switchTenant(switchTo(THERE), request, response))
                .isInstanceOf(UserNotFoundException.class);
        verify(accountService, never()).listMembershipsOf(any());
    }

    @Test
    void myTenants_listsThePersonsMemberships_includingTheCurrentOne() {
        givenMemberships(membership(HERE, 1L, AccountStatus.ACTIVE),
                membership(THERE, 2L, AccountStatus.FROZEN));

        List<MembershipOption> options = controller.myTenants(signedInAt(HERE)).getData();

        // The company the caller is in now has to be in the list: the switcher shows it as the
        // current selection, and a list that omitted it would read as "you may leave but not stay".
        assertThat(options).extracting(MembershipOption::accountId).containsExactlyInAnyOrder(HERE, THERE);
        assertThat(options).extracting(MembershipOption::tenantName).containsExactlyInAnyOrder("Acme", "Globex");
        // Same badges the login picker uses — a non-ACTIVE company is listed but not selectable.
        assertThat(options).filteredOn(option -> THERE.equals(option.accountId()))
                .singleElement()
                .satisfies(option -> {
                    assertThat(option.status()).isEqualTo(AccountStatus.FROZEN);
                    assertThat(option.selectable()).isFalse();
                });
    }
}
