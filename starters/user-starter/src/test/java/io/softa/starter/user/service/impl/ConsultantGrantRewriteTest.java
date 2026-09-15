package io.softa.starter.user.service.impl;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.CacheService;
import io.softa.starter.user.entity.ConsultantAuthorization;
import io.softa.starter.user.entity.ConsultantProfile;
import io.softa.starter.user.service.UserAccountService;

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

    @BeforeEach
    void setUp() {
        service = spy(new ConsultantServiceImpl());
        authorizationService = mock(ConsultantAuthorizationService.class);
        UserAccountService accountService = mock(UserAccountService.class);
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
        grant.setStartDate(start);
        grant.setEndDate(end);
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
    void aChangedEndDateIsWrittenBack() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        when(authorizationService.searchList(any(Filters.class)))
                .thenReturn(List.of(grant(50L, start, LocalDate.of(2026, 6, 30))));

        service.replaceAuthorizations(PROFILE,
                List.of(grant(null, start, LocalDate.of(2026, 12, 31))));

        verify(authorizationService).updateOne(any(ConsultantAuthorization.class));
    }
}
