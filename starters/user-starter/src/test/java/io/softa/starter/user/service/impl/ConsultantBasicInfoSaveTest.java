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
 * <p>So the line is not "does it save" but "how did this save reach the person". A person this save
 * CREATED is seeded from the form; a person the edit form OPENED by id is changed on purpose, by a
 * platform administrator, on a screen that warns what a login identifier change does; a person the
 * create form merely FOUND through the email / mobile lookup is left alone, name included — that is
 * the path the takeover took, and nobody on it meant to change the person. Tests on every side of
 * that line, because each case looks correct on its own.
 */
class ConsultantBasicInfoSaveTest {

    private static final Long PROFILE = 7L;
    /** The mobile the person already signs in with, so a form repeating it changes nothing. */
    private static final String MOBILE = "+6590000000";

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
        identity.setLoginMobile(MOBILE);
        identity.setPassword("hash-read-with-the-row");
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
        f.setAuthorizations(new ConsultantProfileDTO.AuthorizationPatch());
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
        service.save(form("Old Name", "old@zingkey.com", MOBILE));

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

        ConsultantProfileDTO f = form("Jane", "jane@zingkey.com", MOBILE);
        f.setProfileId(null);

        // Refused by the channel that collided — the email is the only identifier the form carries,
        // and the sentence names it so the operator knows which box to change.
        assertThatThrownBy(() -> service.save(f))
                .hasMessage("This email is already used by another consultant profile.");
        // Nothing was written on the way to the refusal — above all not the grant table.
        verify(service, never()).applyAuthorizations(anyLong(), any());
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

        ConsultantProfileDTO f = form("Employee", "employee@zingkey.com", MOBILE);
        f.setProfileId(null);

        assertThat(service.save(f)).isEqualTo(PROFILE);
    }

    @Test
    void aFormWithoutAnEmailIsRefused() {
        // Both identifiers are required. Either-or let a payload create a consultant the form itself
        // refuses, with one fewer way to sign in and no delete to take it back.
        for (String blank : new String[] {null, "", "   "}) {
            assertThatThrownBy(() -> service.save(form("Old Name", blank, MOBILE)))
                    .hasMessageContaining("Please enter a valid email address");
        }
        verify(service, never()).applyAuthorizations(any(), any());
    }

    @Test
    void aFormWithoutAMobileIsRefused() {
        for (String blank : new String[] {null, "", "   "}) {
            assertThatThrownBy(() -> service.save(form("Old Name", "old@zingkey.com", blank)))
                    .hasMessageContaining("Mobile is required");
        }
        verify(service, never()).applyAuthorizations(any(), any());
    }

    @Test
    void theEditFormRenamesThePersonItOpened() {
        // Named by id: the platform administrator opened this profile and is changing it on purpose.
        // The name is global — every company this person belongs to displays it — which is what the
        // screen says before saving, not a reason to refuse.
        service.save(form("Ada Lovelace", "old@zingkey.com", MOBILE));

        ArgumentCaptor<UserProfile> named = ArgumentCaptor.forClass(UserProfile.class);
        verify(profileService).updateOne(named.capture());
        assertThat(named.getValue().getFullName()).isEqualTo("Ada Lovelace");
    }

    @Test
    void theEditFormMovesTheLoginAddressOfThePersonItOpened() {
        service.save(form("Old Name", "Ada@Zingkey.com", " +65 9123-4567 "));

        ArgumentCaptor<UserIdentity> credential = ArgumentCaptor.forClass(UserIdentity.class);
        verify(identityService).updateOne(credential.capture());
        // Canonical spelling: the login query asks for the normalised form, so a value stored as
        // typed is one that query can never find.
        assertThat(credential.getValue().getLoginEmail()).isEqualTo("ada@zingkey.com");
        assertThat(credential.getValue().getLoginMobile()).isEqualTo("+6591234567");
        // A patch, not the row that was read: a password changed since that read is not put back.
        assertThat(credential.getValue().getId()).isEqualTo(11L);
        assertThat(credential.getValue().getPassword()).isNull();
    }

    @Test
    void anAddressSomebodyElseSignsInWithIsRefused() {
        // Login identifiers are unique across the platform — login resolves a person by one of them.
        when(identityService.isIdentifierClaimable("taken@zingkey.com", PROFILE)).thenReturn(false);

        assertThatThrownBy(() -> service.save(form("Old Name", "taken@zingkey.com", MOBILE)))
                .hasMessageContaining("already belongs to someone else");
        verify(identityService, never()).updateOne(any(UserIdentity.class));
    }

    @Test
    void aSaveThatChangesNothingWritesNothing() {
        service.save(form("Old Name", "old@zingkey.com", MOBILE));

        verify(profileService, never()).updateOne(any(UserProfile.class));
        verify(identityService, never()).updateOne(any(UserIdentity.class));
        verify(profileService, never()).evictUserInfo(anyLong());
    }

    /**
     * Each customer's roster shows the consultant by their login email, stamped onto the membership.
     * Refreshing that BEFORE moving the identifier stamped the address just replaced into every
     * company — the change saved, and every customer still saw the old one.
     */
    @Test
    void everyRosterShowsTheNewAddressNotTheOldOne() {
        UserAccount consultantSeat = new UserAccount();
        consultantSeat.setId(100L);
        consultantSeat.setTenantId(500L);
        consultantSeat.setConsultant(Boolean.TRUE);
        consultantSeat.setUsername("old@zingkey.com");
        when(accountService.listMembershipsOf(PROFILE)).thenReturn(List.of(consultantSeat));
        when(accountService.searchList(any(Filters.class))).thenReturn(List.of());

        service.save(form("Old Name", "new@zingkey.com", MOBILE));

        assertThat(consultantSeat.getUsername()).isEqualTo("new@zingkey.com");
        // Once, after both writes — not once for the name and again for the identifiers.
        verify(profileService).evictUserInfo(100L);
    }

    /**
     * Making an existing employee a consultant, from the CREATE form. The lookup finds them by the
     * email typed, and nobody here meant to change them — writing the form's mobile would silently
     * replace the number they sign in with at the company that employs them.
     */
    @Test
    void aPersonTheCreateFormMerelyFoundIsNotWritten() {
        UserIdentity employee = new UserIdentity();
        employee.setId(12L);
        employee.setProfileId(PROFILE);
        employee.setLoginEmail("employee@zingkey.com");
        employee.setLoginMobile("+6580000000");
        when(identityService.findByLoginIdentifier("employee@zingkey.com"))
                .thenReturn(Optional.of(employee));
        doReturn(Optional.empty()).when(service).searchOne(any(Filters.class));
        doReturn(1L).when(service).createOne(any(ConsultantProfile.class));

        ConsultantProfileDTO f = form("Someone Else", "employee@zingkey.com", "+6591234567");
        f.setProfileId(null);
        service.save(f);

        verify(identityService, never()).updateOne(any(UserIdentity.class));
        verify(profileService, never()).updateOne(any(UserProfile.class));
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

        ConsultantProfileDTO f = form("Ada Lovelace", "fresh@zingkey.com", MOBILE);
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

        ConsultantProfileDTO fresh = form("Old Name", "taken@zingkey.com", MOBILE);
        fresh.setProfileId(null);
        assertThatThrownBy(() -> service.save(fresh))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already belongs to someone else");
    }

    @Test
    void anUnchangedCardWritesNothing() {
        // The paired case: a version that wrote unconditionally would satisfy every test above while
        // evicting caches and touching credentials on every save of the grant table alone.
        service.save(form("Old Name", "old@zingkey.com", MOBILE));

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
        service.save(freshPerson(form("Old Name", "Ada@ZingKey.com", MOBILE)));

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
        // A blank name cleared by accident would blank the person in every company. A blank email or
        // mobile no longer gets this far — both are required, asserted on their own above.
        service.save(freshPerson(form("  ", "old@zingkey.com", "+6591234567")));

        verify(profileService, never()).updateOne(any(UserProfile.class));
        ArgumentCaptor<UserIdentity> saved = ArgumentCaptor.forClass(UserIdentity.class);
        verify(identityService).updateOne(saved.capture());
        assertThat(saved.getValue().getLoginMobile()).isEqualTo("+6591234567");
        // The email did not change, so the patch does not carry it at all.
        assertThat(saved.getValue().getLoginEmail()).isNull();
    }
}
