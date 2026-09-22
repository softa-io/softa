package io.softa.starter.user.service.impl;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.CacheService;
import io.softa.starter.user.entity.ConsultantAuthorization;
import io.softa.starter.user.entity.ConsultantProfile;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.service.UserAccountService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Rewriting a consultant's grant table over rows that are already there.
 *
 * <p>The comparison decides one thing — has this grant's period changed — and it used to reach
 * through whatever the database handed back. That is fine for a row this screen wrote, and the
 * screen requires both dates. It is not fine for a row the platform's generic CRUD wrote, which this
 * model's own endpoints still serve to the platform administrator: such a row can carry no dates at
 * all, and the form that would repair it answered with a NullPointerException instead.
 */
class ConsultantGrantRewriteTest {

    private static final Long PROFILE = 7L;
    private static final Long TENANT = 3L;

    private ConsultantServiceImpl service;
    private ConsultantAuthorizationService authorizationService;
    private UserAccountService accountService;

    @BeforeEach
    void setUp() {
        service = spy(new ConsultantServiceImpl());
        authorizationService = mock(ConsultantAuthorizationService.class);
        accountService = mock(UserAccountService.class);
        ReflectionTestUtils.setField(service, "authorizationService", authorizationService);
        ReflectionTestUtils.setField(service, "accountService", accountService);
        ReflectionTestUtils.setField(service, "cacheService", mock(CacheService.class));

        ConsultantProfile profile = new ConsultantProfile();
        profile.setId(1L);
        profile.setProfileId(PROFILE);
        profile.setActive(Boolean.TRUE);
        doReturn(Optional.of(profile)).when(service).searchOne(any(Filters.class));
        when(accountService.listMembershipsOf(PROFILE)).thenReturn(List.of());
    }

    private static ConsultantAuthorization grant(Long id, LocalDate start, LocalDate end) {
        ConsultantAuthorization grant = new ConsultantAuthorization();
        grant.setId(id);
        grant.setProfileId(PROFILE);
        grant.setTenantId(TENANT);
        grant.setEndDate(end);
        // A stored grant names the membership it minted. A null there means one written before the
        // link existed, which the save fills in — its own case below.
        if (id != null) {
            grant.setAccountId(500L);
        }
        return grant;
    }

    @Test
    void aStoredGrantWithNoDatesIsRepairedRatherThanThrownOn() {
        when(authorizationService.searchList(any(Filters.class)))
                .thenReturn(List.of(grant(50L, null, null)));

        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 12, 31);
        assertThatCode(() -> service.replaceAuthorizations(PROFILE, List.of(grant(null, start, end))))
                .doesNotThrowAnyException();

        verify(authorizationService).updateOne(any(ConsultantAuthorization.class));
    }

    @Test
    void anUnchangedPeriodIsStillNotRewritten() {
        // The paired case: answering "changed" for everything would make the null row above pass
        // while turning every save into a write over grants nobody edited.
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 12, 31);
        when(authorizationService.searchList(any(Filters.class)))
                .thenReturn(List.of(grant(50L, start, end)));

        service.replaceAuthorizations(PROFILE, List.of(grant(null, start, end)));

        verify(authorizationService, never()).updateOne(any(ConsultantAuthorization.class));
        verify(authorizationService, never()).createOne(any(ConsultantAuthorization.class));
    }

    @Test
    void aGrantWrittenBeforeTheLinkExistedGetsItOnTheNextSave() {
        // Filled on the next save rather than by a migration: the membership is findable from
        // (profileId, tenantId) either way, and until it is filled the form reads the grant as one
        // that minted nothing — the badge meant for genuinely broken rows.
        LocalDate end = LocalDate.of(2026, 12, 31);
        ConsultantAuthorization unlinked = grant(50L, null, end);
        unlinked.setAccountId(null);
        when(authorizationService.searchList(any(Filters.class))).thenReturn(List.of(unlinked));
        UserAccount minted = new UserAccount();
        minted.setId(500L);
        when(accountService.findMembershipInTenant(TENANT, PROFILE)).thenReturn(Optional.of(minted));

        service.replaceAuthorizations(PROFILE, List.of(grant(null, null, end)));

        ArgumentCaptor<ConsultantAuthorization> linked =
                ArgumentCaptor.forClass(ConsultantAuthorization.class);
        verify(authorizationService).updateOne(linked.capture());
        assertThat(linked.getValue().getAccountId()).isEqualTo(500L);
    }

    @Test
    void aChangedEndDateIsWrittenBack() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        when(authorizationService.searchList(any(Filters.class)))
                .thenReturn(List.of(grant(50L, start, LocalDate.of(2026, 6, 30))));

        service.replaceAuthorizations(PROFILE,
                List.of(grant(null, start, LocalDate.of(2026, 12, 31))));

        verify(authorizationService).updateOne(any(ConsultantAuthorization.class));
    }
}
