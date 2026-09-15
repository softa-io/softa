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
 * The Basic information card has to actually be saved.
 *
 * <p>It was not. {@code save} read the email and mobile into locals, used them only to find or create
 * the person, and wrote them nowhere; the username it never read at all. Every edit to that card was
 * accepted and discarded — the form said it saved, the list came back unchanged, and nothing in
 * between reported anything.
 *
 * <p>The tests assert on what reaches the person and the credential, not on what the endpoint
 * returns: {@code save} answered with the right profile id the whole time it was dropping the data.
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
    void theUsernameReachesThePerson() {
        service.save(form("Ada Lovelace", "old@zingkey.com", null));

        ArgumentCaptor<UserProfile> saved = ArgumentCaptor.forClass(UserProfile.class);
        verify(profileService).updateOne(saved.capture());
        assertThat(saved.getValue().getFullName()).isEqualTo("Ada Lovelace");
    }

    @Test
    void renamingEvictsEveryMembershipsCachedUserInfo() {
        // The cache holds the name and nothing evicts it on a bare update. Keyed per membership, so
        // missing one leaves that tenant serving the old name for a month — to somebody who was just
        // told the change was saved.
        service.save(form("Ada Lovelace", "old@zingkey.com", null));

        verify(profileService).evictUserInfo(100L);
    }

    @Test
    void aChangedEmailReachesTheCredential() {
        service.save(form("Old Name", "ada@zingkey.com", null));

        ArgumentCaptor<UserIdentity> saved = ArgumentCaptor.forClass(UserIdentity.class);
        verify(identityService).updateOne(saved.capture());
        assertThat(saved.getValue().getLoginEmail()).isEqualTo("ada@zingkey.com");
    }

    @Test
    void anIdentifierBelongingToSomebodyElseIsRefusedBeforeTheDatabaseRefusesIt() {
        // Globally unique by index, so this would otherwise surface as a constraint name. The
        // operator's real mistake — typing a live person's address — deserves a sentence.
        when(identityService.isIdentifierClaimable(eq("taken@zingkey.com"), eq(PROFILE))).thenReturn(false);

        assertThatThrownBy(() -> service.save(form("Old Name", "taken@zingkey.com", null)))
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
        service.save(form("Old Name", "old@zingkey.com", "+65 9123-4567"));

        ArgumentCaptor<UserIdentity> saved = ArgumentCaptor.forClass(UserIdentity.class);
        verify(identityService).updateOne(saved.capture());
        assertThat(saved.getValue().getLoginMobile()).isEqualTo("+6591234567");
    }

    @Test
    void anEmailIsStoredLowercasedLikeEverySpellingTheLookupsUse() {
        service.save(form("Old Name", "Ada@ZingKey.com", null));

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
        service.save(form("  ", "", "  "));

        verify(profileService, never()).updateOne(any(UserProfile.class));
        verify(identityService, never()).updateOne(any(UserIdentity.class));
    }
}
