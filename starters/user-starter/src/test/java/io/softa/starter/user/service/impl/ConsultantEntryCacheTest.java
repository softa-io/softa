package io.softa.starter.user.service.impl;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.CacheService;
import io.softa.starter.user.entity.ConsultantProfile;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.service.ConsultantService;
import io.softa.starter.user.service.UserAccountService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The gate's per-request question, and the minute of memory in front of it.
 *
 * <p>{@code stillAuthorized} runs on every request a consultant makes and costs three reads to
 * answer. What makes caching it safe is not the TTL on its own but the pairing: the two ways access
 * ends by somebody's decision — disabled, revoked — evict, so they bite on the next request, and the
 * TTL only ever delays the one that nobody triggers, a grant lapsing by the calendar.
 *
 * <p>So these tests are about that pairing. A cache that never evicted would satisfy the first case
 * and let a consultant removed during an incident keep working for another minute.
 */
class ConsultantEntryCacheTest {

    private static final Long ACCOUNT = 100L;
    private static final Long PROFILE = 7L;
    private static final Long TENANT = 3L;

    private ConsultantAccessCheckerImpl checker;
    private UserAccountService accountService;
    private ConsultantService consultantService;
    private CacheService cacheService;

    @BeforeEach
    void setUp() {
        checker = new ConsultantAccessCheckerImpl();
        accountService = mock(UserAccountService.class);
        consultantService = mock(ConsultantService.class);
        cacheService = mock(CacheService.class);
        ReflectionTestUtils.setField(checker, "accountService", accountService);
        ReflectionTestUtils.setField(checker, "consultantService", consultantService);
        ReflectionTestUtils.setField(checker, "cacheService", cacheService);

        UserAccount account = new UserAccount();
        account.setId(ACCOUNT);
        account.setProfileId(PROFILE);
        account.setTenantId(TENANT);
        account.setConsultant(Boolean.TRUE);
        when(accountService.getById(ACCOUNT)).thenReturn(Optional.of(account));
        when(consultantService.canEnter(PROFILE, TENANT)).thenReturn(true);
    }

    @Test
    void aFreshAnswerIsResolvedAndThenRemembered() {
        assertThat(checker.stillAuthorized(ACCOUNT)).isTrue();

        verify(consultantService).canEnter(PROFILE, TENANT);
        verify(cacheService).save(eq(ConsultantAccessCheckerImpl.cacheKey(ACCOUNT)), eq(true),
                eq(ConsultantAccessCheckerImpl.TTL_SECONDS));
    }

    @Test
    void aRememberedAnswerCostsNoReads() {
        when(cacheService.get(ConsultantAccessCheckerImpl.cacheKey(ACCOUNT), Boolean.class))
                .thenReturn(Boolean.TRUE);

        assertThat(checker.stillAuthorized(ACCOUNT)).isTrue();

        verify(accountService, never()).getById(any());
        verify(consultantService, never()).canEnter(any(), any());
    }

    @Test
    void aRememberedRefusalIsAlsoHonoured() {
        // Not symmetrical by accident: a cache that only remembered "yes" would re-read on every
        // request of the one caller it has already refused, which is the session that retries most.
        when(cacheService.get(ConsultantAccessCheckerImpl.cacheKey(ACCOUNT), Boolean.class))
                .thenReturn(Boolean.FALSE);

        assertThat(checker.stillAuthorized(ACCOUNT)).isFalse();

        verify(consultantService, never()).canEnter(any(), any());
    }

    @Test
    void disablingAConsultantForgetsEveryMembershipsAnswer() {
        ConsultantServiceImpl service = consultantServiceWith(cacheService);

        service.setActive(PROFILE, false);

        verify(cacheService).clear(ConsultantAccessCheckerImpl.cacheKey(ACCOUNT));
    }

    @Test
    void rewritingTheGrantsForgetsEveryMembershipsAnswer() {
        ConsultantServiceImpl service = consultantServiceWith(cacheService);

        service.replaceAuthorizations(PROFILE, List.of());

        verify(cacheService).clear(ConsultantAccessCheckerImpl.cacheKey(ACCOUNT));
    }

    @Test
    void theWriteSideBuildsTheSameKeyTheReadSideStoredUnder() {
        // The one way this pairing fails silently: two spellings of the key. Pinned by asserting the
        // stored key and the cleared key against each other rather than against a literal, so a
        // change to the format cannot pass by being made twice.
        checker.stillAuthorized(ACCOUNT);
        consultantServiceWith(cacheService).setActive(PROFILE, false);

        verify(cacheService).save(eq(ConsultantAccessCheckerImpl.cacheKey(ACCOUNT)), any(), anyInt());
        verify(cacheService).clear(ConsultantAccessCheckerImpl.cacheKey(ACCOUNT));
        verify(cacheService, times(1)).clear(anyString());
    }

    /** A consultant service over the same cache, with just enough wired to reach the eviction. */
    private ConsultantServiceImpl consultantServiceWith(CacheService cache) {
        ConsultantServiceImpl service = spy(new ConsultantServiceImpl());
        ConsultantAuthorizationService authorizations = mock(ConsultantAuthorizationService.class);
        ReflectionTestUtils.setField(service, "cacheService", cache);
        ReflectionTestUtils.setField(service, "accountService", accountService);
        ReflectionTestUtils.setField(service, "authorizationService", authorizations);

        ConsultantProfile profile = new ConsultantProfile();
        profile.setId(1L);
        profile.setProfileId(PROFILE);
        profile.setActive(Boolean.TRUE);
        doReturn(Optional.of(profile)).when(service).searchOne(any(Filters.class));
        doReturn(true).when(service).updateOne(any(ConsultantProfile.class));
        when(authorizations.searchList(any(Filters.class))).thenReturn(List.of());

        UserAccount membership = new UserAccount();
        membership.setId(ACCOUNT);
        when(accountService.listMembershipsOf(PROFILE)).thenReturn(List.of(membership));
        return service;
    }
}
