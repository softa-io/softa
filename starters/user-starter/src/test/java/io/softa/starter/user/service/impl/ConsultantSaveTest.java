package io.softa.starter.user.service.impl;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.domain.Filters;
import io.softa.starter.user.dto.ConsultantProfileDTO;
import io.softa.starter.user.entity.ConsultantProfile;
import io.softa.starter.user.entity.UserIdentity;
import io.softa.starter.user.service.UserAccountService;
import io.softa.starter.user.service.UserIdentityService;
import io.softa.starter.user.service.UserProfileService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Saving a consultant, and the case the form does not draw but the tenant picker requires.
 *
 * <p>The requirement would have scoped the duplicate check to consultants, so a consultant could share
 * an email with an employee. That cannot be built: login identifiers are globally unique, so a
 * second profile carrying the address is impossible — and it is also unnecessary, because the person
 * holding it IS the consultant. Reusing them is what makes "employee at company A, consultant for
 * company B" expressible; two profiles sharing an address could never be shown as one picker.
 */
class ConsultantSaveTest {

    private static final Long EXISTING_PERSON = 7L;

    private final UserAccountService accountService = mock(UserAccountService.class);
    private final ConsultantAuthorizationService authorizationService =
            mock(ConsultantAuthorizationService.class);
    private final UserIdentityService identityService = mock(UserIdentityService.class);
    private final UserProfileService profileService = mock(UserProfileService.class);
    private final ConsultantServiceImpl consultantService = spy(new ConsultantServiceImpl());

    ConsultantSaveTest() {
        ReflectionTestUtils.setField(consultantService, "accountService", accountService);
        ReflectionTestUtils.setField(consultantService, "authorizationService", authorizationService);
        ReflectionTestUtils.setField(consultantService, "identityService", identityService);
        ReflectionTestUtils.setField(consultantService, "profileService", profileService);
        // No consultant record yet, and no grant work — this test is about which person is chosen.
        doReturn(Optional.empty()).when(consultantService).searchOne(any(Filters.class));
        doReturn(1L).when(consultantService).createOne(any(ConsultantProfile.class));
        doNothing().when(consultantService).replaceAuthorizations(any(), any());
    }

    private static ConsultantProfileDTO form(String email, String mobile) {
        ConsultantProfileDTO f = new ConsultantProfileDTO();
        f.setUsername("Alice");
        f.setEmail(email);
        f.setMobile(mobile);
        return f;
    }

    @Test
    void anAddressThatAlreadyBelongsToSomeoneMakesThatPersonTheConsultant() {
        UserIdentity held = new UserIdentity();
        held.setProfileId(EXISTING_PERSON);
        when(identityService.findByLoginIdentifier("alice@acme.com")).thenReturn(Optional.of(held));

        Long profileId = consultantService.save(form("alice@acme.com", "+8613800138000"));

        assertThat(profileId).isEqualTo(EXISTING_PERSON);
        // The load-bearing half: no second person is minted for an address that already has one.
        verify(profileService, never()).createPersonForJoin(anyString());
    }

    @Test
    void aMobileMatchesToo_becauseTheOperatorTypesWhicheverTheyKnow() {
        UserIdentity held = new UserIdentity();
        held.setProfileId(EXISTING_PERSON);
        when(identityService.findByLoginIdentifier("new@acme.com")).thenReturn(Optional.empty());
        when(identityService.findByLoginIdentifier("+8613800138000")).thenReturn(Optional.of(held));

        assertThat(consultantService.save(form("new@acme.com", "+8613800138000")))
                .isEqualTo(EXISTING_PERSON);
        verify(profileService, never()).createPersonForJoin(anyString());
    }

    @Test
    void anUnknownAddressMintsANewPerson() {
        when(identityService.findByLoginIdentifier(anyString())).thenReturn(Optional.empty());
        when(profileService.createPersonForJoin("fresh@acme.com")).thenReturn(99L);

        assertThat(consultantService.save(form("fresh@acme.com", "+8613800138001"))).isEqualTo(99L);
    }

    @Test
    void aNewConsultantIsEnabledUnlessTheFormSaysOtherwise() {
        when(identityService.findByLoginIdentifier(anyString())).thenReturn(Optional.empty());
        when(profileService.createPersonForJoin(anyString())).thenReturn(99L);

        consultantService.save(form("fresh@acme.com", "+8613800138001"));

        org.mockito.ArgumentCaptor<ConsultantProfile> saved =
                org.mockito.ArgumentCaptor.forClass(ConsultantProfile.class);
        verify(consultantService).createOne(saved.capture());
        assertThat(saved.getValue().getActive()).isTrue();
    }

    @Test
    void theGrantTableIsAppliedAsAWholeSet() {
        when(identityService.findByLoginIdentifier(anyString())).thenReturn(Optional.empty());
        when(profileService.createPersonForJoin(anyString())).thenReturn(99L);
        ConsultantProfileDTO f = form("fresh@acme.com", "+8613800138001");
        ConsultantProfileDTO.AuthorizationRow row = new ConsultantProfileDTO.AuthorizationRow();
        row.setTenantId(100L);
        row.setStartDate(LocalDate.of(2026, 9, 1));
        row.setEndDate(LocalDate.of(2026, 9, 30));
        f.setAuthorizations(List.of(row));

        consultantService.save(f);

        // Handed over as one set, so "what the screen showed" and "what was stored" cannot drift.
        verify(consultantService).replaceAuthorizations(org.mockito.ArgumentMatchers.eq(99L),
                org.mockito.ArgumentMatchers.argThat(list -> list.size() == 1
                        && list.get(0).getTenantId().equals(100L)));
    }
}
