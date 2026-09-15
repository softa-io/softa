package io.softa.starter.user.service.impl;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.domain.Filters;
import io.softa.starter.user.dto.ConsultantRowDTO;
import io.softa.starter.user.entity.ConsultantAuthorization;
import io.softa.starter.user.entity.ConsultantProfile;
import io.softa.starter.user.entity.UserIdentity;
import io.softa.starter.user.entity.UserProfile;
import io.softa.starter.user.service.UserIdentityService;
import io.softa.starter.user.service.UserProfileService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Consultant Profiles list costs a fixed number of reads, not a number that grows with it.
 *
 * <p>Each row shows three things that live off the consultant record — the person's name, their
 * login identifiers, the companies whose grant covers today — and each used to be fetched for one
 * consultant at a time. That is 3n+1 queries against a platform-wide table with no bound on its
 * size, and the page that shows it has no paging.
 *
 * <p>Asserted as "never per row" rather than as a count, because a count would also pass for a
 * version that batched two of the three.
 */
class ConsultantListBatchingTest {

    private ConsultantServiceImpl service;
    private UserProfileService profileService;
    private UserIdentityService identityService;
    private ConsultantAuthorizationService authorizationService;

    @BeforeEach
    void setUp() {
        service = spy(new ConsultantServiceImpl());
        profileService = mock(UserProfileService.class);
        identityService = mock(UserIdentityService.class);
        authorizationService = mock(ConsultantAuthorizationService.class);
        ReflectionTestUtils.setField(service, "profileService", profileService);
        ReflectionTestUtils.setField(service, "identityService", identityService);
        ReflectionTestUtils.setField(service, "authorizationService", authorizationService);

        doReturn(List.of(consultant(1L), consultant(2L), consultant(3L)))
                .when(service).searchList(any(Filters.class));
        when(profileService.searchList(any(Filters.class)))
                .thenReturn(List.of(person(1L, "Ada"), person(2L, "Grace"), person(3L, "Alan")));
        when(identityService.searchList(any(Filters.class)))
                .thenReturn(List.of(identity(1L, "ada@zingkey.com"), identity(2L, "grace@zingkey.com"),
                        identity(3L, "alan@zingkey.com")));
        when(authorizationService.searchList(any(Filters.class)))
                .thenReturn(List.of(liveGrant(1L, 10L), liveGrant(2L, 11L)));
    }

    private static ConsultantProfile consultant(Long profileId) {
        ConsultantProfile profile = new ConsultantProfile();
        profile.setId(profileId);
        profile.setProfileId(profileId);
        profile.setActive(Boolean.TRUE);
        return profile;
    }

    private static UserProfile person(Long id, String name) {
        UserProfile person = new UserProfile();
        person.setId(id);
        person.setFullName(name);
        return person;
    }

    private static UserIdentity identity(Long profileId, String email) {
        UserIdentity identity = new UserIdentity();
        identity.setProfileId(profileId);
        identity.setLoginEmail(email);
        return identity;
    }

    private static ConsultantAuthorization liveGrant(Long profileId, Long tenantId) {
        ConsultantAuthorization grant = new ConsultantAuthorization();
        grant.setProfileId(profileId);
        grant.setTenantId(tenantId);
        grant.setStartDate(LocalDate.now().minusDays(1));
        grant.setEndDate(LocalDate.now().plusDays(1));
        return grant;
    }

    @Test
    void threeConsultantsCostThreeSatelliteReads_notNine() {
        List<ConsultantRowDTO> rows = service.list(null);

        assertThat(rows).hasSize(3);
        verify(profileService, times(1)).searchList(any(Filters.class));
        verify(identityService, times(1)).searchList(any(Filters.class));
        verify(authorizationService, times(1)).searchList(any(Filters.class));
        verify(profileService, never()).getById(anyLong());
        verify(identityService, never()).findByProfile(anyLong());
    }

    @Test
    void eachRowStillCarriesItsOwnPersonIdentifiersAndLiveGrants() {
        // The paired case: batching is only correct if the rows are matched back up by profile.
        // A version that read three lists and handed every row the first entry would pass the
        // counting test above.
        List<ConsultantRowDTO> rows = service.list(null);

        ConsultantRowDTO grace = rows.stream()
                .filter(r -> r.getProfileId().equals(2L)).findFirst().orElseThrow();
        assertThat(grace.getUsername()).isEqualTo("Grace");
        assertThat(grace.getEmail()).isEqualTo("grace@zingkey.com");
        assertThat(grace.getAuthorizedTenants()).singleElement()
                .satisfies(badge -> assertThat(badge.getTenantId()).isEqualTo(11L));

        ConsultantRowDTO alan = rows.stream()
                .filter(r -> r.getProfileId().equals(3L)).findFirst().orElseThrow();
        assertThat(alan.getUsername()).isEqualTo("Alan");
        assertThat(alan.getAuthorizedTenants()).isEmpty();
    }

    @Test
    void theSearchStillMatchesNameAndEmail() {
        assertThat(service.list("grace").stream().map(ConsultantRowDTO::getProfileId))
                .containsExactly(2L);
        assertThat(service.list("ALAN").stream().map(ConsultantRowDTO::getProfileId))
                .containsExactly(3L);
    }

    @Test
    void aPersonWithNoNameYetStillLists() {
        // A person created through /join carries no full name until somebody types one, and
        // Collectors.toMap refuses a null value — so keying the batch by name rather than by person
        // turned the whole page into a NullPointerException over one nameless consultant.
        when(profileService.searchList(any(Filters.class)))
                .thenReturn(List.of(person(1L, null), person(2L, "Grace"), person(3L, "Alan")));

        List<ConsultantRowDTO> rows = service.list(null);

        assertThat(rows).hasSize(3);
        assertThat(rows.stream().filter(r -> r.getProfileId().equals(1L)).findFirst().orElseThrow()
                .getUsername()).isNull();
    }

    @Test
    void noConsultantsReadsNoSatellitesAtAll() {
        doReturn(List.of()).when(service).searchList(any(Filters.class));

        assertThat(service.list(null)).isEmpty();
        verify(profileService, never()).searchList(any(Filters.class));
    }
}
