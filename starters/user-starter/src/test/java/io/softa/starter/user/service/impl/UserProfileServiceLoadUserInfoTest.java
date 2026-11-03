package io.softa.starter.user.service.impl;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.constant.RedisConstant;
import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.context.UserInfo;
import io.softa.framework.orm.service.CacheService;
import io.softa.framework.orm.service.TenantInfoService;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.entity.UserProfile;
import io.softa.starter.user.enums.AccountStatus;
import io.softa.starter.user.service.UserAccountService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link UserProfileServiceImpl#loadUserInfo} — the framework-facing rebuild that turns
 * {@code user-info:} into a real cache.
 *
 * <p>{@code ContextBuilder} calls it on the request path, before any context exists, so it must
 * answer "cannot resolve this session" with {@code null} rather than by throwing: an exception here
 * surfaces as a server error on a condition the caller already handles.
 */
class UserProfileServiceLoadUserInfoTest {

    private static final Long USER_ID = 42L;
    private static final Long PROFILE_ID = 7L;
    private static final Long TENANT_ID = 1L;

    private CacheService cacheService;
    private UserAccountService accountService;
    private TenantInfoService tenantInfoService;
    private UserProfileServiceImpl service;

    @BeforeEach
    void setUp() {
        // Single-tenant by default so buildUserInfo's tenant assertions stay out of the way; the
        // one test that wants them turns it back on.
        SystemConfig config = new SystemConfig();
        config.setEnableMultiTenancy(false);
        SystemConfig.env = config;

        cacheService = mock(CacheService.class);
        accountService = mock(UserAccountService.class);
        tenantInfoService = mock(TenantInfoService.class);
        // Spied so getById (the profile read, inherited from EntityServiceImpl) can be stubbed
        // without standing up an ORM.
        service = spy(new UserProfileServiceImpl());
        ReflectionTestUtils.setField(service, "cacheService", cacheService);
        ReflectionTestUtils.setField(service, "accountService", accountService);
        ReflectionTestUtils.setField(service, "tenantInfoService", tenantInfoService);
        when(tenantInfoService.isTenantActive(any())).thenReturn(true);
    }

    private void accountIs(AccountStatus status) {
        UserAccount account = new UserAccount();
        account.setId(USER_ID);
        account.setProfileId(PROFILE_ID);
        account.setTenantId(TENANT_ID);
        account.setStatus(status);
        when(accountService.getById(USER_ID)).thenReturn(Optional.of(account));

        UserProfile profile = new UserProfile();
        profile.setId(PROFILE_ID);
        profile.setFullName("Ada");
        // doReturn, not when(...): the spy would run the real inherited getById while stubbing it.
        doReturn(Optional.of(profile)).when(service).getById(PROFILE_ID);
    }

    @Test
    void servesTheCachedEntryWithoutReadingTheDatabase() {
        UserInfo cached = new UserInfo();
        cached.setUserId(USER_ID);
        when(cacheService.get(eq(RedisConstant.USER_INFO + USER_ID), eq(UserInfo.class))).thenReturn(cached);

        assertThat(service.loadUserInfo(USER_ID)).isSameAs(cached);
        verify(accountService, org.mockito.Mockito.never()).getById(any());
    }

    @Test
    void rebuildsFromTheDatabaseAndRecachesIt() {
        // Re-caching is the point: without it every request after an eviction pays the two reads,
        // for a value that only changes when something evicts it again.
        when(cacheService.get(eq(RedisConstant.USER_INFO + USER_ID), eq(UserInfo.class))).thenReturn(null);
        accountIs(AccountStatus.ACTIVE);

        UserInfo built = service.loadUserInfo(USER_ID);

        assertThat(built).isNotNull();
        assertThat(built.getName()).isEqualTo("Ada");
        assertThat(built.getActive()).isTrue();
        verify(cacheService).save(eq(RedisConstant.USER_INFO + USER_ID), any(UserInfo.class), anyInt());
    }

    @Test
    void aLegacyLockedAccount_isActive_asItIsAtSignIn() {
        // Sign-in lets LOCKED in; read as inactive here, every session it got would end on its first
        // request and hand out a re-entry token, round and round.
        when(cacheService.get(eq(RedisConstant.USER_INFO + USER_ID), eq(UserInfo.class))).thenReturn(null);
        accountIs(AccountStatus.LOCKED);

        assertThat(service.loadUserInfo(USER_ID).getActive()).isTrue();
    }

    @Test
    void reportsAFrozenAccountAsInactiveRatherThanRefusingToBuild() {
        // The whole reason the HR-side eviction exists. A frozen account must still produce a
        // UserInfo — it is the `active=false` on it that lets ContextBuilder's gate destroy the
        // session; refusing to build one would send the caller down the "missing user" path
        // instead, which is the indiscriminate behaviour this change removes.
        when(cacheService.get(eq(RedisConstant.USER_INFO + USER_ID), eq(UserInfo.class))).thenReturn(null);
        accountIs(AccountStatus.FROZEN);

        UserInfo built = service.loadUserInfo(USER_ID);

        assertThat(built).isNotNull();
        assertThat(built.getActive()).isFalse();
    }

    @Test
    void aMissingAccountAnswersNullInsteadOfThrowing() {
        when(cacheService.get(eq(RedisConstant.USER_INFO + USER_ID), eq(UserInfo.class))).thenReturn(null);
        when(accountService.getById(USER_ID)).thenReturn(Optional.empty());

        assertThat(service.loadUserInfo(USER_ID)).isNull();
    }

    @Test
    void aMissingProfileAnswersNullInsteadOfThrowing() {
        when(cacheService.get(eq(RedisConstant.USER_INFO + USER_ID), eq(UserInfo.class))).thenReturn(null);
        accountIs(AccountStatus.ACTIVE);
        doReturn(Optional.empty()).when(service).getById(PROFILE_ID);

        assertThat(service.loadUserInfo(USER_ID)).isNull();
    }

    @Test
    void aNonBusinessFailureAlsoAnswersNull() {
        // The build fails two ways and they are siblings, not one hierarchy: a missing account or
        // profile raises BusinessException, while everything reached through Assert raises
        // IllegalArgumentException. Catching only the first would let that one out of here and reach
        // the filter as a server error, on a session that simply cannot be resolved.
        when(cacheService.get(eq(RedisConstant.USER_INFO + USER_ID), eq(UserInfo.class))).thenReturn(null);
        when(accountService.getById(USER_ID))
                .thenThrow(new IllegalArgumentException("something asserted inside the build"));

        assertThat(service.loadUserInfo(USER_ID)).isNull();
    }

    @Test
    void leavesTheTenantGateToTheCaller() {
        // buildUserInfo's own tenant assertion is inert under the cross-tenant waiver this method
        // needs, and that is the right split rather than a gap: ContextBuilder re-checks
        // isTenantActive on every request and, unlike the assertion, DESTROYS the session when it
        // fails. Rebuilding here and letting that gate fire is strictly the stronger of the two.
        SystemConfig config = new SystemConfig();
        config.setEnableMultiTenancy(true);
        SystemConfig.env = config;
        when(cacheService.get(eq(RedisConstant.USER_INFO + USER_ID), eq(UserInfo.class))).thenReturn(null);
        accountIs(AccountStatus.ACTIVE);
        when(tenantInfoService.isTenantActive(TENANT_ID)).thenReturn(false);

        UserInfo built = service.loadUserInfo(USER_ID);

        assertThat(built).isNotNull();
        assertThat(built.getTenantId()).isEqualTo(TENANT_ID);
    }

    @Test
    void readsTheAccountCrossTenantAndWithoutScopeEnforcement() {
        // UserAccount is multiTenant, and the tenant this read would be filtered by is exactly what
        // it is trying to find out. Nothing is bound this early, so ContextHolder hands back an
        // empty Context: without the waiver the filter becomes `tenant_id = NULL`, matches nothing,
        // and every session is reported as a missing user — the rebuild silently doing nothing while
        // looking like it works.
        when(cacheService.get(eq(RedisConstant.USER_INFO + USER_ID), eq(UserInfo.class))).thenReturn(null);
        accountIs(AccountStatus.ACTIVE);
        boolean[] seen = new boolean[2];
        when(accountService.getById(USER_ID)).thenAnswer(invocation -> {
            seen[0] = ContextHolder.getContext().isCrossTenant();
            seen[1] = ContextHolder.getContext().isSkipPermissionCheck();
            UserAccount account = new UserAccount();
            account.setId(USER_ID);
            account.setProfileId(PROFILE_ID);
            account.setTenantId(TENANT_ID);
            account.setStatus(AccountStatus.ACTIVE);
            return Optional.of(account);
        });

        assertThat(service.loadUserInfo(USER_ID)).isNotNull();

        assertThat(seen[0]).as("crossTenant during the account read").isTrue();
        assertThat(seen[1]).as("skipPermissionCheck during the account read").isTrue();
    }

    @Test
    void doesNotLeakTheWaiverToTheCallersContext() {
        // The waiver is scoped to the rebuild. Asserted against a context that is actually BOUND —
        // an unbound ContextHolder hands back a fresh empty Context on every call, so it can never
        // show a leak and the assertion would pass whatever the method did.
        when(cacheService.get(eq(RedisConstant.USER_INFO + USER_ID), eq(UserInfo.class))).thenReturn(null);
        accountIs(AccountStatus.ACTIVE);
        Context caller = new Context();

        ContextHolder.runWith(caller, () -> service.loadUserInfo(USER_ID));

        assertThat(caller.isCrossTenant()).isFalse();
        assertThat(caller.isSkipPermissionCheck()).isFalse();
    }

    @Test
    void aNullIdAnswersNull() {
        assertThat(service.loadUserInfo(null)).isNull();
    }
}
