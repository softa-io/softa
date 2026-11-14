package io.softa.starter.user.service.impl;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.context.UserInfo;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.domain.Filters;
import io.softa.starter.user.dto.UserProfileDTO;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.entity.UserIdentity;
import io.softa.starter.user.enums.AccountStatus;
import io.softa.starter.user.service.UserIdentityService;
import io.softa.starter.user.service.UserProfileService;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Creating an account for someone who ALREADY works somewhere else.
 *
 * <p>This is the one case where the same address appearing in a second company is the intended
 * outcome rather than a collision, and it used to be unreachable through the product entirely.
 * Every creation path — Invite Admin, create user, create employee — funnels through
 * {@code registerInvitedUser}, which refused any identifier already used in ANY tenant. That also
 * kept the /join flow's own find-or-create from ever firing: it needs an account carrying the
 * person's identifier, and that account was exactly what could not be created. The only way to
 * produce a person with two tenants was to write the row by hand.
 *
 * <p>What must NOT come back is the person's existing identity. A create form that echoed the name
 * behind an address would answer "who owns this address?" for anyone able to create an account.
 */
class SecondTenantAccountTest {

    private static final Long PERSON = 7L;
    private static final Long THIS_TENANT = 2L;
    private static final Long NEW_ACCOUNT = 200L;
    private static final String EMAIL = "ada@example.com";
    private static final String MOBILE = "+6591234567";

    private final UserIdentityService identityService = mock(UserIdentityService.class);
    private final UserProfileService profileService = mock(UserProfileService.class);
    private final UserAccountServiceImpl accountService = spy(new UserAccountServiceImpl());
    private final io.softa.starter.user.service.UserSecurityLogService securityLog =
            AccountServiceTestSupport.quiet(accountService);

    {
        // The people these contacts name still work somewhere, so none of their numbers is dormant.
        UserAccount live = new UserAccount();
        live.setStatus(AccountStatus.ACTIVE);
        org.mockito.Mockito.doReturn(java.util.List.of(live)).when(accountService)
                .listMembershipsOf(org.mockito.ArgumentMatchers.any());
    }

    SecondTenantAccountTest() {
        ReflectionTestUtils.setField(accountService, "identityService", identityService);
        ReflectionTestUtils.setField(accountService, "profileService", profileService);
        doReturn(NEW_ACCOUNT).when(accountService).createOne(any(UserAccount.class));
        // No membership for this person here, and no account holding these contacts here, unless a
        // test says otherwise.
        doReturn(List.of()).when(accountService).searchList(any(Filters.class));
        doReturn(Optional.empty()).when(accountService).findMembershipInTenant(any(), any());
        when(profileService.registerUserProfile(anyLong(), any(UserProfileDTO.class)))
                .thenReturn(new UserInfo());
        when(profileService.linkAccountToPerson(anyLong(), anyLong())).thenReturn(new UserInfo());
    }

    /** Runs the call inside a tenant, which is where the remaining uniqueness rule applies. */
    private void inThisTenant(Runnable body) {
        Context ctx = new Context();
        ctx.setTenantId(THIS_TENANT);
        ContextHolder.runWith(ctx, body);
    }

    private static UserIdentity personHolding(String identifier) {
        UserIdentity identity = new UserIdentity();
        identity.setProfileId(PERSON);
        identity.setLoginEmail(identifier);
        return identity;
    }

    @Test
    void anAddressThatBelongsToSomeoneLinksToThatPerson() {
        when(identityService.findByLoginIdentifier(EMAIL))
                .thenReturn(Optional.of(personHolding(EMAIL)));

        inThisTenant(() -> accountService.registerInvitedUser(EMAIL, null, "Ada L"));

        verify(profileService).linkAccountToPerson(NEW_ACCOUNT, PERSON);
        verify(profileService, never()).registerUserProfile(anyLong(), any(UserProfileDTO.class));
    }

    @Test
    void anAddressNobodyHoldsStillMintsAPerson() {
        when(identityService.findByLoginIdentifier(EMAIL)).thenReturn(Optional.empty());

        inThisTenant(() -> accountService.registerInvitedUser(EMAIL, null, "Ada L"));

        verify(profileService).registerUserProfile(anyLong(), any(UserProfileDTO.class));
        verify(profileService, never()).linkAccountToPerson(anyLong(), anyLong());
    }

    @Test
    void theNewAccountLandsPendingSoTheyStillHaveToAccept() {
        // The whole safety of letting an admin name an existing person: the membership is not
        // usable until that person accepts through /join.
        when(identityService.findByLoginIdentifier(EMAIL))
                .thenReturn(Optional.of(personHolding(EMAIL)));

        inThisTenant(() -> accountService.registerInvitedUser(EMAIL, null, "Ada L"));

        verify(accountService).createOne(org.mockito.ArgumentMatchers
                .argThat((UserAccount a) -> a.getStatus() == AccountStatus.PENDING));
    }

    @Test
    void theExistingPersonsNameIsNotEchoedBack() {
        // The account carries what the caller typed, not what the person is called elsewhere.
        when(identityService.findByLoginIdentifier(EMAIL))
                .thenReturn(Optional.of(personHolding(EMAIL)));

        inThisTenant(() -> accountService.registerInvitedUser(EMAIL, null, "Typed Name"));

        verify(accountService).createOne(org.mockito.ArgumentMatchers
                .argThat((UserAccount a) -> "Typed Name".equals(a.getNickname())));
    }

    @Test
    void aDuplicateInsideThisTenantIsStillRefused() {
        // The per-tenant rule survives: uk_user_account_tenant_email is what it mirrors.
        when(identityService.findByLoginIdentifier(EMAIL)).thenReturn(Optional.empty());
        UserAccount sameTenantHolder = new UserAccount();
        sameTenantHolder.setId(999L);
        sameTenantHolder.setEmail(EMAIL);
        doReturn(List.of(sameTenantHolder)).when(accountService).searchList(any(Filters.class));

        inThisTenant(() -> assertThatThrownBy(() -> accountService.registerInvitedUser(EMAIL, null, "Ada"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Email already exists"));
    }

    @Test
    void thePersonAlreadyBeingAMemberHereIsRefused() {
        when(identityService.findByLoginIdentifier(EMAIL))
                .thenReturn(Optional.of(personHolding(EMAIL)));
        UserAccount theirMembershipHere = new UserAccount();
        theirMembershipHere.setId(999L);
        theirMembershipHere.setTenantId(THIS_TENANT);
        theirMembershipHere.setProfileId(PERSON);
        // No account holds the contact, but this person already has a membership here.
        doReturn(Optional.of(theirMembershipHere))
                .when(accountService).findMembershipInTenant(THIS_TENANT, PERSON);

        inThisTenant(() -> assertThatThrownBy(() -> accountService.registerInvitedUser(EMAIL, null, "Ada"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already a member of this company"));
    }

    @Test
    void twoIdentifiersNamingTwoDifferentPeopleIsRefused() {
        // One account cannot carry two people's credentials, and no later step could untangle it.
        when(identityService.findByLoginIdentifier(EMAIL))
                .thenReturn(Optional.of(personHolding(EMAIL)));
        UserIdentity somebodyElse = new UserIdentity();
        somebodyElse.setProfileId(99L);
        when(identityService.findByLoginIdentifier(MOBILE)).thenReturn(Optional.of(somebodyElse));

        inThisTenant(() -> assertThatThrownBy(() -> accountService.registerInvitedUser(EMAIL, MOBILE, "Ada"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("two different"));
    }

    @Test
    void bothIdentifiersNamingTheSamePersonIsFine() {
        when(identityService.findByLoginIdentifier(EMAIL))
                .thenReturn(Optional.of(personHolding(EMAIL)));
        when(identityService.findByLoginIdentifier(MOBILE))
                .thenReturn(Optional.of(personHolding(MOBILE)));

        inThisTenant(() -> accountService.registerInvitedUser(EMAIL, MOBILE, "Ada"));

        verify(profileService).linkAccountToPerson(NEW_ACCOUNT, PERSON);
    }

    @Test
    void aMobileOnlyAccountResolvesByMobile() {
        // The lookup uses whichever identifier is present — a mobile-only employee is normal.
        when(identityService.findByLoginIdentifier(MOBILE))
                .thenReturn(Optional.of(personHolding(MOBILE)));

        inThisTenant(() -> accountService.registerInvitedUser(null, MOBILE, "Ada"));

        verify(profileService).linkAccountToPerson(NEW_ACCOUNT, PERSON);
    }
}
