package io.softa.starter.user.service.impl;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.domain.Filters;
import io.softa.starter.user.entity.ConsultantAuthorization;
import io.softa.starter.user.entity.ConsultantProfile;
import io.softa.starter.user.service.UserAccountService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * Who may enter a company as a consultant, and when.
 *
 * <p>Three independent ways the answer is no — not a consultant, consultant disabled, grant not
 * covering today — and the point of {@code canEnter} is that no caller gets to check only two of
 * them. The dates are inclusive on both ends and read against the calendar, so a grant lapses with
 * nothing having run overnight; that is what {@code todayIs} pins here.
 */
class ConsultantAccessTest {

    private static final Long PROFILE = 7L;
    private static final Long TENANT = 100L;

    private final UserAccountService accountService = mock(UserAccountService.class);
    private final ConsultantAuthorizationService authorizationService =
            mock(ConsultantAuthorizationService.class);
    private final io.softa.framework.orm.service.TenantInfoService tenantInfoService =
            mock(io.softa.framework.orm.service.TenantInfoService.class);
    private final ConsultantServiceImpl consultantService = spy(new ConsultantServiceImpl());

    ConsultantAccessTest() {
        ReflectionTestUtils.setField(consultantService, "accountService", accountService);
        ReflectionTestUtils.setField(consultantService, "authorizationService", authorizationService);
        ReflectionTestUtils.setField(consultantService, "tenantInfoService", tenantInfoService);
        // Companies are open unless a test says otherwise — the existing cases are about grants.
        when(tenantInfoService.isTenantActive(any())).thenReturn(true);
        todayIs(LocalDate.of(2026, 9, 3));
    }

    private void todayIs(LocalDate date) {
        doReturn(date).when(consultantService).today();
    }

    /** An enabled consultant record for PROFILE, or none at all when {@code enabled} is null. */
    private void givenConsultant(Boolean enabled) {
        if (enabled == null) {
            doReturn(Optional.empty()).when(consultantService).searchOne(any(Filters.class));
            return;
        }
        ConsultantProfile profile = new ConsultantProfile();
        profile.setId(1L);
        profile.setProfileId(PROFILE);
        profile.setActive(enabled);
        doReturn(Optional.of(profile)).when(consultantService).searchOne(any(Filters.class));
    }

    private void givenGrant(LocalDate start, LocalDate end) {
        ConsultantAuthorization grant = new ConsultantAuthorization();
        grant.setProfileId(PROFILE);
        grant.setTenantId(TENANT);
        grant.setStartDate(start);
        grant.setEndDate(end);
        doReturn(Optional.of(grant)).when(authorizationService).searchOne(any(Filters.class));
        doReturn(List.of(grant)).when(authorizationService).searchList(any(Filters.class));
    }

    @Test
    void aLiveGrantAdmits() {
        givenConsultant(true);
        givenGrant(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));

        assertThat(consultantService.canEnter(PROFILE, TENANT)).isTrue();
        assertThat(consultantService.enterableTenantIds(PROFILE)).containsExactly(TENANT);
    }

    @Test
    void bothEndsAreInclusive() {
        givenConsultant(true);
        givenGrant(LocalDate.of(2026, 9, 3), LocalDate.of(2026, 9, 3));

        // A one-day grant admits on its one day — the boundary is where an off-by-one would lock
        // someone out of the day they were given.
        assertThat(consultantService.canEnter(PROFILE, TENANT)).isTrue();
    }

    @Test
    void anExpiredGrantStopsAdmittingWithNothingHavingRun() {
        givenConsultant(true);
        givenGrant(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 2));   // ended yesterday

        assertThat(consultantService.canEnter(PROFILE, TENANT)).isFalse();
        // Absent, not present-and-unselectable: a lapsed consultancy is not access on hold.
        assertThat(consultantService.enterableTenantIds(PROFILE)).isEmpty();
    }

    @Test
    void aGrantThatHasNotStartedDoesNotAdmitEarly() {
        givenConsultant(true);
        givenGrant(LocalDate.of(2026, 9, 4), LocalDate.of(2026, 9, 30));

        assertThat(consultantService.canEnter(PROFILE, TENANT)).isFalse();
    }

    @Test
    void disablingStopsEveryCompanyAtOnce_withoutTouchingTheGrants() {
        givenConsultant(false);
        givenGrant(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));   // still live on paper

        assertThat(consultantService.canEnter(PROFILE, TENANT)).isFalse();
        assertThat(consultantService.enterableTenantIds(PROFILE)).isEmpty();
        // The grant is untouched, so re-enabling restores exactly this access with no grant edits.
        assertThat(consultantService.authorizationsOf(PROFILE)).hasSize(1);
    }

    @Test
    void someoneWhoIsNotAConsultantNeverEnters() {
        givenConsultant(null);
        givenGrant(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));

        // A stray grant without a consultant record must not admit — the three checks are AND, not OR.
        assertThat(consultantService.canEnter(PROFILE, TENANT)).isFalse();
        assertThat(consultantService.isConsultant(PROFILE)).isFalse();
    }

    @Test
    void anUngrantedCompanyIsRefusedEvenForAnEnabledConsultant() {
        givenConsultant(true);
        doReturn(Optional.empty()).when(authorizationService).searchOne(any(Filters.class));

        assertThat(consultantService.canEnter(PROFILE, 999L)).isFalse();
    }

    @Test
    void aFrozenCompanyIsClosedEvenToAConsultantWithALiveGrant() {
        // PRD CE5. The company's own state outranks the grant, and a consultant is the one
        // principal who would otherwise walk straight in: their data access is unrestricted and
        // their menus come from the plan, so nothing further down the stack would stop them.
        givenConsultant(true);
        givenGrant(LocalDate.now().minusDays(1), LocalDate.now().plusDays(1));
        when(tenantInfoService.isTenantActive(TENANT)).thenReturn(false);

        assertThat(consultantService.canEnter(PROFILE, TENANT)).isFalse();
    }

    @Test
    void withoutATenantDirectoryTheGrantAloneDecides() {
        // A deployment without tenant-starter has no such state to consult; refusing everyone
        // because the question cannot be asked would close the door on the whole feature.
        ReflectionTestUtils.setField(consultantService, "tenantInfoService", null);
        givenConsultant(true);
        givenGrant(LocalDate.now().minusDays(1), LocalDate.now().plusDays(1));

        assertThat(consultantService.canEnter(PROFILE, TENANT)).isTrue();
    }
}
