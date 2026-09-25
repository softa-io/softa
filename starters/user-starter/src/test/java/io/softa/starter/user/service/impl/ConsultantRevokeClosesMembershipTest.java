package io.softa.starter.user.service.impl;

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
import io.softa.starter.user.entity.UserIdentity;
import io.softa.starter.user.entity.UserProfile;
import io.softa.starter.user.enums.AccountStatus;
import io.softa.starter.user.service.UserAccountService;
import io.softa.starter.user.service.UserIdentityService;
import io.softa.starter.user.service.UserProfileService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Revoking a consultant's authorization closes the membership it minted; it does not delete it.
 *
 * <p>Deleting would have satisfied the same request — the tenant's roster should stop listing a
 * consultant who is no longer authorized — and it is what the review meeting first asked for. It
 * fails on two counts that only show up later.
 *
 * <p>The account is the actor this tenant's own audit log points at. Remove it and the authorship of
 * everything the consultant did while they had access goes with it, months after the revocation, on
 * a screen nobody was looking at when the decision was made.
 *
 * <p>And {@code (tenantId, profileId)} is unique. A deleted-then-re-authorized consultant needs a
 * NEW account, so one person's history in one company splits into two identities with a gap in
 * between — and every audit row from before the gap points at a row that no longer exists.
 *
 * <p>DEACTIVATED gets the clearing-out for free: {@code listMembershipsOf} already hides those rows,
 * so the consultant stops seeing the company and the roster stops listing it, with no rule written
 * for consultants specifically.
 */
class ConsultantRevokeClosesMembershipTest {

    private static final Long PROFILE = 7L;
    private static final Long TENANT = 3L;

    private ConsultantServiceImpl service;
    private ConsultantAuthorizationService authorizationService;
    private UserAccountService accountService;
    private UserProfileService profileService;
    private UserIdentityService identityService;

    @BeforeEach
    void setUp() {
        service = spy(new ConsultantServiceImpl());
        authorizationService = mock(ConsultantAuthorizationService.class);
        accountService = mock(UserAccountService.class);
        profileService = mock(UserProfileService.class);
        identityService = mock(UserIdentityService.class);
        ReflectionTestUtils.setField(service, "profileService", profileService);
        ReflectionTestUtils.setField(service, "identityService", identityService);
        ReflectionTestUtils.setField(service, "authorizationService", authorizationService);
        ReflectionTestUtils.setField(service, "accountService", accountService);
        ReflectionTestUtils.setField(service, "cacheService", mock(CacheService.class));

        ConsultantProfile profile = new ConsultantProfile();
        profile.setId(1L);
        profile.setProfileId(PROFILE);
        profile.setActive(Boolean.TRUE);
        doReturn(Optional.of(profile)).when(service).searchOne(any(Filters.class));

        // One grant on file, for TENANT, which the caller is about to drop.
        ConsultantAuthorization standing = new ConsultantAuthorization();
        standing.setId(11L);
        standing.setProfileId(PROFILE);
        standing.setTenantId(TENANT);
        when(authorizationService.searchList(any(Filters.class))).thenReturn(List.of(standing));
        when(accountService.listMembershipsOf(PROFILE)).thenReturn(List.of(membership(AccountStatus.ACTIVE)));
    }

    @Test
    void aMintedMembershipCarriesTheConsultantsNameForTheTenantToRead() {
        // Every other business column on a consultant's membership is empty — they are all the
        // tenant's own data about its own staff — so without the name the roster shows a row of em
        // dashes, and "you may suspend this" means nothing against a record nobody can identify.
        UserProfile person = new UserProfile();
        person.setId(PROFILE);
        person.setFullName("Ada Lovelace");
        when(profileService.getById(PROFILE)).thenReturn(Optional.of(person));
        UserIdentity identity = new UserIdentity();
        identity.setProfileId(PROFILE);
        identity.setLoginEmail("ada@zingkey.com");
        when(identityService.findByProfile(PROFILE)).thenReturn(Optional.of(identity));
        when(accountService.findMembershipInTenant(TENANT, PROFILE)).thenReturn(Optional.empty());
        when(authorizationService.searchList(any(Filters.class))).thenReturn(List.of());

        ConsultantAuthorization wanted = new ConsultantAuthorization();
        wanted.setTenantId(TENANT);
        service.replaceAuthorizations(PROFILE, List.of(wanted));

        ArgumentCaptor<UserAccount> minted = ArgumentCaptor.forClass(UserAccount.class);
        verify(accountService).createOne(minted.capture());
        assertThat(minted.getValue().getNickname()).isEqualTo("Ada Lovelace");
        assertThat(minted.getValue().getUsername()).isEqualTo("ada@zingkey.com");
        // Free in this company, so it is written. The guarded case has its own test below.
        assertThat(minted.getValue().getEmail()).isEqualTo("ada@zingkey.com");
    }

    @Test
    void aNewGrantIsWrittenAlreadyNamingTheMembershipItMinted() {
        // Minted first, so the grant can be created with the link in place. Creating the grant
        // first needs a second write to fill it in, and a failure between the two leaves a grant
        // that names no membership — which the form reads as one that minted nothing.
        UserProfile person = new UserProfile();
        person.setId(PROFILE);
        person.setFullName("Ada Lovelace");
        when(profileService.getById(PROFILE)).thenReturn(Optional.of(person));
        when(identityService.findByProfile(PROFILE)).thenReturn(Optional.empty());
        when(accountService.findMembershipInTenant(TENANT, PROFILE)).thenReturn(Optional.empty());
        when(accountService.createOne(any(UserAccount.class))).thenReturn(500L);
        when(authorizationService.searchList(any(Filters.class))).thenReturn(List.of());

        ConsultantAuthorization wanted = new ConsultantAuthorization();
        wanted.setTenantId(TENANT);
        service.replaceAuthorizations(PROFILE, List.of(wanted));

        ArgumentCaptor<ConsultantAuthorization> written =
                ArgumentCaptor.forClass(ConsultantAuthorization.class);
        verify(authorizationService).createOne(written.capture());
        assertThat(written.getValue().getAccountId()).isEqualTo(500L);
    }

    @Test
    void theWorkEmailIsLeftBlankRatherThanCollideWithAnEmployeeWhoHoldsIt() {
        // UserAccount.email is unique per (tenantId, email). A consultant's address is a PLATFORM
        // login identifier, and some unrelated employee of this customer may already carry it as
        // their work contact — writing it blindly would make that collision refuse the
        // authorization, blocking a consultant out of a company for a reason nothing to do with
        // them. The name and username carry no index, so the row is still identifiable.
        UserProfile person = new UserProfile();
        person.setId(PROFILE);
        person.setFullName("Ada Lovelace");
        when(profileService.getById(PROFILE)).thenReturn(Optional.of(person));
        UserIdentity identity = new UserIdentity();
        identity.setProfileId(PROFILE);
        identity.setLoginEmail("shared@acme.com");
        when(identityService.findByProfile(PROFILE)).thenReturn(Optional.of(identity));
        when(accountService.findMembershipInTenant(TENANT, PROFILE)).thenReturn(Optional.empty());
        when(authorizationService.searchList(any(Filters.class))).thenReturn(List.of());

        UserAccount employeeHoldingIt = new UserAccount();
        employeeHoldingIt.setId(999L);
        employeeHoldingIt.setTenantId(TENANT);
        employeeHoldingIt.setEmail("shared@acme.com");
        when(accountService.searchList(any(Filters.class))).thenReturn(List.of(employeeHoldingIt));

        ConsultantAuthorization wanted = new ConsultantAuthorization();
        wanted.setTenantId(TENANT);
        service.replaceAuthorizations(PROFILE, List.of(wanted));

        ArgumentCaptor<UserAccount> minted = ArgumentCaptor.forClass(UserAccount.class);
        verify(accountService).createOne(minted.capture());
        assertThat(minted.getValue().getEmail()).isNull();
        assertThat(minted.getValue().getNickname()).isEqualTo("Ada Lovelace");
        assertThat(minted.getValue().getUsername()).isEqualTo("shared@acme.com");
    }

    @Test
    void revokingClosesTheMembershipRatherThanDeletingIt() {
        when(accountService.findMembershipInTenant(TENANT, PROFILE))
                .thenReturn(Optional.of(membership(AccountStatus.ACTIVE)));

        service.replaceAuthorizations(PROFILE, List.of());

        verify(authorizationService).deleteById(11L);
        ArgumentCaptor<UserAccount> closed = ArgumentCaptor.forClass(UserAccount.class);
        verify(accountService).updateOne(closed.capture());
        assertThat(closed.getValue().getStatus()).isEqualTo(AccountStatus.DEACTIVATED);
        // The row itself survives, because the audit log names it.
        verify(accountService, never()).deleteById(any());
    }

    @Test
    void anEmploymentInThatCompanyIsNotTouched() {
        // The same person may be an employee of the company they consulted for — the design says so
        // explicitly. Revoking the consultancy must not close the job.
        UserAccount employment = membership(AccountStatus.ACTIVE);
        employment.setConsultant(Boolean.FALSE);
        when(accountService.findMembershipInTenant(TENANT, PROFILE)).thenReturn(Optional.of(employment));

        service.replaceAuthorizations(PROFILE, List.of());

        verify(authorizationService).deleteById(11L);
        verify(accountService, never()).updateOne(any(UserAccount.class));
    }

    @Test
    void revokingLeavesACustomersSuspensionExactlyAsTheySetIt() {
        // The two-step hole this closes: revoke used to overwrite FROZEN with DEACTIVATED, and
        // re-authorizing revives exactly that — so a platform operator could undo a customer's
        // suspension by revoking and re-granting, with nothing on either screen saying so. The
        // guard on the revival exists to stop that and was being reached with the evidence gone.
        when(accountService.findMembershipInTenant(TENANT, PROFILE))
                .thenReturn(Optional.of(membership(AccountStatus.FROZEN)));

        service.replaceAuthorizations(PROFILE, List.of());

        verify(authorizationService).deleteById(11L);
        // The grant goes; the customer's state is not the platform's to clear.
        verify(accountService, never()).updateOne(any(UserAccount.class));
    }

    @Test
    void reAuthorizingRevivesTheSameMembership() {
        UserAccount closedRow = membership(AccountStatus.DEACTIVATED);
        when(accountService.findMembershipInTenant(TENANT, PROFILE)).thenReturn(Optional.of(closedRow));
        when(authorizationService.searchList(any(Filters.class))).thenReturn(List.of());

        ConsultantAuthorization wanted = new ConsultantAuthorization();
        wanted.setTenantId(TENANT);
        service.replaceAuthorizations(PROFILE, List.of(wanted));

        ArgumentCaptor<UserAccount> revived = ArgumentCaptor.forClass(UserAccount.class);
        verify(accountService).updateOne(revived.capture());
        assertThat(revived.getValue().getStatus()).isEqualTo(AccountStatus.ACTIVE);
        // Revived, not replaced: the unique key would refuse a second row, and the audit log points
        // at this one.
        verify(accountService, never()).createOne(any(UserAccount.class));
    }

    @Test
    void reAuthorizingDoesNotOverturnTheCustomersOwnSuspension() {
        // A membership the TENANT froze stays frozen. Re-authorizing is the platform answering its
        // own question, and the two vetoes are not each other's override — otherwise a customer who
        // suspended a consultant would find them back inside the moment the platform renewed a
        // grant the customer was never asked about.
        UserAccount frozen = membership(AccountStatus.FROZEN);
        when(accountService.findMembershipInTenant(TENANT, PROFILE)).thenReturn(Optional.of(frozen));
        when(authorizationService.searchList(any(Filters.class))).thenReturn(List.of());

        ConsultantAuthorization wanted = new ConsultantAuthorization();
        wanted.setTenantId(TENANT);
        service.replaceAuthorizations(PROFILE, List.of(wanted));

        verify(accountService, never()).updateOne(any(UserAccount.class));
    }

    private static UserAccount membership(AccountStatus status) {
        UserAccount account = new UserAccount();
        account.setId(101L);
        account.setProfileId(PROFILE);
        account.setTenantId(TENANT);
        account.setConsultant(Boolean.TRUE);
        account.setStatus(status);
        return account;
    }
}
