package io.softa.starter.user.service.impl;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.CacheService;
import io.softa.starter.user.dto.ConsultantProfileDTO;
import io.softa.starter.user.entity.ConsultantProfile;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.entity.UserIdentity;
import io.softa.starter.user.entity.UserProfile;
import io.softa.starter.user.service.UserAccountService;
import io.softa.starter.user.service.UserIdentityService;
import io.softa.starter.user.service.UserProfileService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the Basic information card may write, and about whom.
 *
 * <p>Two failures meet here, and fixing the first produced the second. Originally the card was read
 * into locals and written nowhere — every edit accepted and discarded. Writing it then applied the
 * form's name, email and mobile to whichever person the lookup landed on, which for an existing
 * person is an account takeover: the lookup matches on EITHER channel, so a victim's mobile plus the
 * attacker's email finds the victim and moves their login address, and a code login then arrives as
 * them.
 *
 * <p>So the line is not "does it save" but "whose row is this". A person this save CREATED is seeded
 * from the form; a person who already existed is left alone, name included. Tests on both sides of
 * that line, because each half looks correct on its own.
 */
class ConsultantBasicInfoSaveTest {

    private static final Long PROFILE = 7L;

    private ConsultantServiceImpl service;
    private UserProfileService profileService;
    private UserIdentityService identityService;
    private UserAccountService accountService;
    private ConsultantAuthorizationService authorizationService;
    private CacheService cacheService;

    @BeforeEach
    void setUp() {
        service = spy(new ConsultantServiceImpl());
        profileService = mock(UserProfileService.class);
        identityService = mock(UserIdentityService.class);
        accountService = mock(UserAccountService.class);
        authorizationService = mock(ConsultantAuthorizationService.class);
        cacheService = mock(CacheService.class);
        ReflectionTestUtils.setField(service, "cacheService", cacheService);
        ReflectionTestUtils.setField(service, "profileService", profileService);
        ReflectionTestUtils.setField(service, "identityService", identityService);
        ReflectionTestUtils.setField(service, "accountService", accountService);
        ReflectionTestUtils.setField(service, "authorizationService", authorizationService);

        // An existing consultant, so save() takes the edit path.
        ConsultantProfile existing = new ConsultantProfile();
        existing.setId(1L);
        existing.setProfileId(PROFILE);
        existing.setActive(Boolean.TRUE);
        doReturn(Optional.of(existing)).when(service).searchOne(any(Filters.class));
        doReturn(true).when(service).updateOne(any(ConsultantProfile.class));

        UserProfile person = new UserProfile();
        person.setId(PROFILE);
        person.setFullName("Old Name");
        when(profileService.getById(PROFILE)).thenReturn(Optional.of(person));
        when(profileService.updateOne(any(UserProfile.class))).thenReturn(true);
        doNothing().when(profileService).evictUserInfo(anyLong());

        UserIdentity identity = new UserIdentity();
        identity.setId(11L);
        identity.setProfileId(PROFILE);
        identity.setLoginEmail("old@zingkey.com");
        when(identityService.findByProfile(PROFILE)).thenReturn(Optional.of(identity));
        when(identityService.updateOne(any(UserIdentity.class))).thenReturn(true);
        when(identityService.isIdentifierClaimable(any(), eq(PROFILE))).thenReturn(true);

        UserAccount membership = new UserAccount();
        membership.setId(100L);
        when(accountService.listMembershipsOf(PROFILE)).thenReturn(List.of(membership));
        when(authorizationService.searchList(any(Filters.class))).thenReturn(List.of());
    }

    private ConsultantProfileDTO form(String username, String email, String mobile) {
        ConsultantProfileDTO f = new ConsultantProfileDTO();
        f.setProfileId(PROFILE);
        f.setUsername(username);
        f.setEmail(email);
        f.setMobile(mobile);
        f.setActive(Boolean.TRUE);
        f.setAuthorizations(List.of());
        return f;
    }

    @Test
    void aMembershipMintedBeforeNamesExistedGetsOneOnTheNextSave() {
        // The rows that need this most are the ones whose name never changes: minted empty, and an
        // operator saving the form without touching the username would have left them empty for
        // good. The roster shows those as a line of em dashes, which is the state the customer
        // cannot act on.
        UserAccount stale = new UserAccount();
        stale.setId(501L);
        stale.setProfileId(PROFILE);
        stale.setTenantId(9L);
        stale.setConsultant(Boolean.TRUE);
        when(accountService.listMembershipsOf(PROFILE)).thenReturn(List.of(stale));

        // "Old Name" is what the person is already called, so nothing about them changes.
        service.save(form("Old Name", "old@zingkey.com", null));

        ArgumentCaptor<UserAccount> named = ArgumentCaptor.forClass(UserAccount.class);
        verify(accountService).updateOne(named.capture());
        assertThat(named.getValue().getNickname()).isEqualTo("Old Name");
    }

    /**
     * The same form, on the create path — no id, and nobody holds either identifier yet.
     *
     * <p>Canonical spelling only matters where a credential is written at all, and after the
     * takeover fix that is a person this save just made.
     */
    private ConsultantProfileDTO freshPerson(ConsultantProfileDTO f) {
        when(identityService.findByLoginIdentifier(any())).thenReturn(Optional.empty());
        when(profileService.createPersonForJoin(any())).thenReturn(PROFILE);
        f.setProfileId(null);
        return f;
    }

    /**
     * The create form is not a back door into somebody else's grant table.
     *
     * <p>It shows only the grants just typed, while the save applies that table as a whole SET. Going
     * on would delete every grant the person already holds and close the memberships behind them —
     * with nothing on screen having named any of them, and the operator believing they had created
     * something.
     */
    @Test
    void creatingOverSomebodyWhoIsAlreadyAConsultantIsRefused() {
        UserIdentity held = new UserIdentity();
        held.setProfileId(PROFILE);
        when(identityService.findByLoginIdentifier("jane@zingkey.com")).thenReturn(Optional.of(held));
        // The fixture's searchOne already answers with an existing ConsultantProfile for PROFILE.

        ConsultantProfileDTO f = form("Jane", "jane@zingkey.com", null);
        f.setProfileId(null);

        assertThatThrownBy(() -> service.save(f))
                .hasMessageContaining("already a consultant");
        // Nothing was written on the way to the refusal — above all not the grant table.
        verify(service, never()).replaceAuthorizations(anyLong(), any());
    }

    @Test
    void anEmployeeWhoIsNotYetAConsultantIsStillReachable() {
        // The one-person-two-hats case the lookup exists for: an employee of company A becoming a
        // consultant for company B is found, not refused.
        UserIdentity held = new UserIdentity();
        held.setProfileId(PROFILE);
        when(identityService.findByLoginIdentifier("employee@zingkey.com")).thenReturn(Optional.of(held));
        doReturn(Optional.empty()).when(service).searchOne(any(Filters.class));
        doReturn(1L).when(service).createOne(any(ConsultantProfile.class));

        ConsultantProfileDTO f = form("Employee", "employee@zingkey.com", null);
        f.setProfileId(null);

        assertThat(service.save(f)).isEqualTo(PROFILE);
    }

    @Test
    void aMobileOnlyConsultantCanBeSaved() {
        // The person who has no email at all. Requiring one made their profile unsaveable: this
        // screen will not write an email onto somebody who already exists, so the field is read-only
        // and blank, and extending their grant or disabling them became impossible.
        service.save(form("Old Name", null, "+6591234567"));

        verify(service).replaceAuthorizations(eq(PROFILE), any());
    }

    @Test
    void aFormWithNeitherChannelIsRefused() {
        assertThatThrownBy(() -> service.save(form("Old Name", null, null)))
                .hasMessageContaining("email or a mobile");
    }

    @Test
    void anExistingPersonIsNotRenamedByThisScreen() {
        service.save(form("Ada Lovelace", "old@zingkey.com", null));

        // The name is global — it is what every company this person belongs to displays. A form about
        // a consultancy does not get to change who somebody is.
        verify(profileService, never()).updateOne(any(UserProfile.class));
    }



    @Test
    void anExistingPersonsLoginAddressIsNotMoved() {
        // This used to be asserted the other way round, which is how the takeover was pinned as
        // intended behaviour: submit somebody else's address and their credential followed.
        service.save(form("Old Name", "ada@zingkey.com", null));

        verify(identityService, never()).updateOne(any(UserIdentity.class));
    }

    /**
     * The takeover, written out as the request that performs it.
     *
     * <p>No profile id is supplied and no password is needed. The lookup tries the email first, finds
     * nobody — it is the attacker's own — and then tries the mobile, which is the victim's. From
     * there the form's email is the victim's new login address, and a code login arrives as them.
     *
     * <p>The one check that existed cannot catch it: "does this address belong to someone else" is
     * asked about the address being moved TO, and the attacker owns it.
     */
    @Test
    void avictimFoundByMobileKeepsTheirLoginAddress() {
        UserIdentity victim = new UserIdentity();
        victim.setId(11L);
        victim.setProfileId(PROFILE);
        victim.setLoginEmail("victim@zingkey.com");
        victim.setLoginMobile("+6591234567");
        when(identityService.findByLoginIdentifier("attacker@evil.com")).thenReturn(Optional.empty());
        when(identityService.findByLoginIdentifier("+6591234567")).thenReturn(Optional.of(victim));
        // An ordinary employee, so the "already a consultant" refusal does not fire and the request
        // reaches the credential write it is really aimed at. Both doors matter: one of them closing
        // is not a reason to stop asserting the other.
        doReturn(Optional.empty()).when(service).searchOne(any(Filters.class));
        doReturn(1L).when(service).createOne(any(ConsultantProfile.class));

        ConsultantProfileDTO f = form("Attacker", "attacker@evil.com", "+6591234567");
        f.setProfileId(null);   // the id is not needed — the mobile finds them
        service.save(f);

        verify(identityService, never()).updateOne(any(UserIdentity.class));
        verify(profileService, never()).updateOne(any(UserProfile.class));
    }

    @Test
    void aPersonThisSaveCreatedIsSeededFromTheForm() {
        // The other side of the line. Nothing is being taken from anyone — without this the
        // consultant's name comes back as the email address createPersonForJoin names them with.
        when(identityService.findByLoginIdentifier(any())).thenReturn(Optional.empty());
        when(profileService.createPersonForJoin("fresh@zingkey.com")).thenReturn(PROFILE);

        ConsultantProfileDTO f = form("Ada Lovelace", "fresh@zingkey.com", null);
        f.setProfileId(null);
        service.save(f);

        ArgumentCaptor<UserProfile> named = ArgumentCaptor.forClass(UserProfile.class);
        verify(profileService).updateOne(named.capture());
        assertThat(named.getValue().getFullName()).isEqualTo("Ada Lovelace");

        ArgumentCaptor<UserIdentity> credential = ArgumentCaptor.forClass(UserIdentity.class);
        verify(identityService).updateOne(credential.capture());
        assertThat(credential.getValue().getLoginEmail()).isEqualTo("fresh@zingkey.com");

        // The cache holds the name per MEMBERSHIP, and nothing evicts it on a bare update.
        verify(profileService).evictUserInfo(100L);
    }

    @Test
    void anIdentifierBelongingToSomebodyElseIsRefusedBeforeTheDatabaseRefusesIt() {
        // Globally unique by index, so this would otherwise surface as a constraint name. The
        // operator's real mistake — typing a live person's address — deserves a sentence.
        when(identityService.findByLoginIdentifier(any())).thenReturn(Optional.empty());
        when(profileService.createPersonForJoin("taken@zingkey.com")).thenReturn(PROFILE);
        when(identityService.isIdentifierClaimable(eq("taken@zingkey.com"), eq(PROFILE))).thenReturn(false);

        ConsultantProfileDTO fresh = form("Old Name", "taken@zingkey.com", null);
        fresh.setProfileId(null);
        assertThatThrownBy(() -> service.save(fresh))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already belongs to someone else");
    }

    @Test
    void anUnchangedCardWritesNothing() {
        // The paired case: a version that wrote unconditionally would satisfy every test above while
        // evicting caches and touching credentials on every save of the grant table alone.
        service.save(form("Old Name", "old@zingkey.com", null));

        verify(profileService, never()).updateOne(any(UserProfile.class));
        verify(identityService, never()).updateOne(any(UserIdentity.class));
    }

    @Test
    void aMobileIsStoredInTheSpellingTheLoginQueryAsksFor() {
        // The form lets an operator type a number however they like, and this used to store it that
        // way. Every lookup normalises first, so a row written as "+65 9123-4567" is one the login
        // query — which asks for "+6591234567" — cannot find: the consultant simply cannot sign in
        // by mobile, and no migration rewrites such a row.
        service.save(freshPerson(form("Old Name", "old@zingkey.com", "+65 9123-4567")));

        ArgumentCaptor<UserIdentity> saved = ArgumentCaptor.forClass(UserIdentity.class);
        verify(identityService).updateOne(saved.capture());
        assertThat(saved.getValue().getLoginMobile()).isEqualTo("+6591234567");
    }

    @Test
    void anEmailIsStoredLowercasedLikeEverySpellingTheLookupsUse() {
        service.save(freshPerson(form("Old Name", "Ada@ZingKey.com", null)));

        ArgumentCaptor<UserIdentity> saved = ArgumentCaptor.forClass(UserIdentity.class);
        verify(identityService).updateOne(saved.capture());
        assertThat(saved.getValue().getLoginEmail()).isEqualTo("ada@zingkey.com");
    }

    @Test
    void reTypingTheSameMobileInAnotherSpellingWritesNothing() {
        // The paired case for the fix above: comparing what was typed against what is stored would
        // call an unchanged number a change and rewrite the credential on every save.
        UserIdentity identity = new UserIdentity();
        identity.setId(11L);
        identity.setProfileId(PROFILE);
        identity.setLoginEmail("old@zingkey.com");
        identity.setLoginMobile("+6591234567");
        when(identityService.findByProfile(PROFILE)).thenReturn(Optional.of(identity));

        service.save(form("Old Name", "old@zingkey.com", "+65 9123 4567"));

        verify(identityService, never()).updateOne(any(UserIdentity.class));
    }

    @Test
    void aBlankFieldMeansNotSuppliedRatherThanClearMyLogin() {
        // One cleared by accident locks the person out, and nothing on this screen would explain why.
        // The mobile carries the save — blanking every channel is refused outright, which is a
        // different rule, asserted on its own above.
        service.save(freshPerson(form("  ", "", "+6591234567")));

        verify(profileService, never()).updateOne(any(UserProfile.class));
        ArgumentCaptor<UserIdentity> saved = ArgumentCaptor.forClass(UserIdentity.class);
        verify(identityService).updateOne(saved.capture());
        // Written because it was supplied; the blank email is left exactly as it was.
        assertThat(saved.getValue().getLoginMobile()).isEqualTo("+6591234567");
        assertThat(saved.getValue().getLoginEmail()).isEqualTo("old@zingkey.com");
    }
}
