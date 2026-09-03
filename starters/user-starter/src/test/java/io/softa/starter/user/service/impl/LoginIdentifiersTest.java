package io.softa.starter.user.service.impl;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.CacheService;
import io.softa.starter.user.entity.UserIdentity;
import io.softa.starter.user.entity.UserProfile;
import io.softa.starter.user.service.UserIdentityService;
import io.softa.starter.user.util.LoginIdentifiers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * One spelling for a login identifier, wherever it is stored, looked up or hashed.
 *
 * <p>The three used to disagree — the unknown-identifier counter hashed a trimmed, lowercased form
 * while the identity lookup queried the raw string — and the disagreement was an existence oracle
 * at the login form (see {@link PasswordLockoutTest}). What is asserted here is the other half of
 * the fix: the seeding writes the same form the lookup queries, so an identifier can be found by
 * any spelling of itself.
 */
class LoginIdentifiersTest {

    @Test
    void normalisation_trimsAndLowercases_andLeavesADialCodeMobileAlone() {
        assertThat(LoginIdentifiers.normalize("  NOBODY@Acme.com ")).isEqualTo("nobody@acme.com");
        assertThat(LoginIdentifiers.normalize(" +6591234567 ")).isEqualTo("+6591234567");
        assertThat(LoginIdentifiers.normalize("   ")).isNull();
        assertThat(LoginIdentifiers.normalize(null)).isNull();
    }

    @Test
    void aMobileTypedWithSeparators_isTheSameIdentifierAsTheBareNumber() {
        // "+65 9123-4567" is how a person types the number; "+6591234567" is how the identity was
        // seeded. One number, and a code sent to either must find the same identity.
        assertThat(LoginIdentifiers.normalize("+65 9123-4567")).isEqualTo("+6591234567");
        assertThat(LoginIdentifiers.normalize("+65\u00A09123\u00A04567")).isEqualTo("+6591234567");
        assertThat(LoginIdentifiers.normalize("9123 4567")).isEqualTo("91234567");
        // An email's internal space is not a separator: it names a different mailbox.
        assertThat(LoginIdentifiers.normalize("ada smith@acme.com")).isEqualTo("ada smith@acme.com");
    }

    @Test
    void theLookup_byAMobileTypedWithSeparators_queriesTheBareNumber_andTheTypedSpelling() {
        // Load-bearing for "resolves the same identity": the seeded spelling reaches the database,
        // so the row seeded as +6591234567 is found from +65 9123-4567. The mobile column is ALSO
        // asked for the typed spelling — rows seeded before the fold hold it, and no migration
        // rewrites them — while the email column gets the one canonical form.
        UserIdentityServiceImpl identityService = spy(new UserIdentityServiceImpl(mock(CacheService.class)));
        doReturn(Optional.empty()).when(identityService).searchOne(any(Filters.class));

        identityService.findByLoginIdentifier("+65 9123-4567");

        ArgumentCaptor<Filters> filters = ArgumentCaptor.forClass(Filters.class);
        verify(identityService, times(2)).searchOne(filters.capture());
        assertThat(filters.getAllValues().get(0).toString())
                .contains("loginEmail").contains("\"+6591234567\"").doesNotContain("9123-");
        assertThat(filters.getAllValues().get(1).toString())
                .contains("loginMobile").contains("\"+6591234567\"").contains("\"+65 9123-4567\"");
    }

    @Test
    void loginSpellings_areTheCanonicalFormAndTheTypedOne_forAMobileOnly() {
        assertThat(LoginIdentifiers.loginSpellings("+65 9123-4567"))
                .containsExactly("+6591234567", "+65 9123-4567");
        assertThat(LoginIdentifiers.loginSpellings(" +6591234567 ")).containsExactly("+6591234567");
        // Nothing inside an email was ever folded, so it has exactly one stored spelling.
        assertThat(LoginIdentifiers.loginSpellings(" ALICE@acme.com")).containsExactly("alice@acme.com");
        assertThat(LoginIdentifiers.loginSpellings("  ")).isEmpty();
        assertThat(LoginIdentifiers.workContactSpellings(" +65 9123-4567 "))
                .containsExactly("+6591234567", "+65 9123-4567");
        assertThat(LoginIdentifiers.workContactSpellings(" Ada@Acme.com ")).containsExactly("Ada@Acme.com");
    }

    @Test
    void aRowSeededBeforeTheFold_isStillFound_byTheSpellingItWasSeededWith() {
        // Load-bearing for the pre-fold rows: user_identity.login_mobile still holds "+65 9123-4567"
        // and nothing rewrites it. A lookup asking only for "+6591234567" misses it — the person
        // cannot sign in or reset by mobile, and an unbound /join mints a second identity for them.
        // The store answers by exact equality, as the database does.
        UserIdentityServiceImpl identityService = spy(new UserIdentityServiceImpl(mock(CacheService.class)));
        UserIdentity legacy = new UserIdentity();
        legacy.setId(11L);
        legacy.setProfileId(7L);
        legacy.setLoginMobile("+65 9123-4567");
        UserIdentity folded = new UserIdentity();
        folded.setId(12L);
        folded.setProfileId(8L);
        folded.setLoginMobile("+6591234567");
        doAnswer(inv -> {
            String query = inv.getArgument(0).toString();
            return query.contains("loginMobile") && query.contains("\"" + legacy.getLoginMobile() + "\"")
                    ? Optional.of(legacy) : Optional.empty();
        }).when(identityService).searchOne(any(Filters.class));

        assertThat(identityService.findByLoginIdentifier("+65 9123-4567")).contains(legacy);
        assertThat(identityService.findByLoginIdentifier(" +65 9123-4567 ")).contains(legacy);

        // And the fold's own promise still holds: a row seeded canonical is found from the typed form.
        doAnswer(inv -> {
            String query = inv.getArgument(0).toString();
            return query.contains("loginMobile") && query.contains("\"" + folded.getLoginMobile() + "\"")
                    ? Optional.of(folded) : Optional.empty();
        }).when(identityService).searchOne(any(Filters.class));
        assertThat(identityService.findByLoginIdentifier("+65 9123-4567")).contains(folded);
    }

    @Test
    void theLoginPaths_handTheLookupsTheTypedForm_andKeyTheCodeOnTheCanonicalOne() {
        // The service is where the typed spelling would be lost: it normalises for the code key
        // and the counters, and used to hand that same canonical string to the lookups, so no
        // caller could ever reach a pre-fold row. Load-bearing: the lookups see the typed form.
        UserIdentityService identityService = mock(UserIdentityService.class);
        io.softa.starter.user.service.UserAccountService accountService =
                mock(io.softa.starter.user.service.UserAccountService.class);
        VerificationCodeGuard codeGuard = mock(VerificationCodeGuard.class);
        LoginServiceImpl loginService = new LoginServiceImpl();
        ReflectionTestUtils.setField(loginService, "identityService", identityService);
        ReflectionTestUtils.setField(loginService, "consultantService",
                mock(io.softa.starter.user.service.ConsultantService.class));
        ReflectionTestUtils.setField(loginService, "accountService", accountService);
        ReflectionTestUtils.setField(loginService, "codeGuard", codeGuard);
        when(identityService.findByLoginIdentifier(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> loginService.authenticateByCode(" +65 9123-4567 ", "123456"))
                .isInstanceOf(io.softa.framework.base.exception.BusinessException.class);

        verify(codeGuard).verify("+6591234567", "123456");
        verify(accountService).isWorkContactShared("+65 9123-4567");
        verify(identityService).findByLoginIdentifier("+65 9123-4567");
    }

    @Test
    void theLookup_queriesTheCanonicalForm_notWhatWasTyped() {
        // The known branch of the login path: a leading space or a capital must reach the database
        // as the value the seeding wrote, or the row is never found and the identifier "does not
        // exist" for exactly as long as it is misspelt.
        UserIdentityServiceImpl identityService = spy(new UserIdentityServiceImpl(mock(CacheService.class)));
        doReturn(Optional.empty()).when(identityService).searchOne(any(Filters.class));

        identityService.findByLoginIdentifier(" ALICE@acme.com");

        ArgumentCaptor<Filters> filters = ArgumentCaptor.forClass(Filters.class);
        verify(identityService, times(2)).searchOne(filters.capture());
        assertThat(filters.getAllValues())
                .allSatisfy(f -> assertThat(f.toString()).contains("\"alice@acme.com\"").doesNotContain("ALICE"));
    }

    @Test
    void theClaimCheck_asksInTheCanonicalForm() {
        UserIdentityServiceImpl identityService = spy(new UserIdentityServiceImpl(mock(CacheService.class)));
        doReturn(List.of()).when(identityService).searchList(any(Filters.class));

        identityService.isIdentifierClaimable(" ALICE@acme.com", 7L);

        ArgumentCaptor<Filters> filters = ArgumentCaptor.forClass(Filters.class);
        verify(identityService).searchList(filters.capture());
        assertThat(filters.getValue().toString()).contains("\"alice@acme.com\"");
    }

    @Test
    void aPersonMintedOnJoin_getsTheIdentifierInCanonicalForm() {
        // The address the invitation carries is the work contact as HR typed it. The identifier
        // seeded from it is what login will query, so it is written the way login will ask.
        UserIdentityService identityService = mock(UserIdentityService.class);
        UserProfileServiceImpl profileService = spy(new UserProfileServiceImpl());
        ReflectionTestUtils.setField(profileService, "identityService", identityService);
        doReturn(42L).when(profileService).createOne(any(UserProfile.class));

        profileService.createPersonForJoin(" Ada@Acme.com ");

        ArgumentCaptor<UserIdentity> seeded = ArgumentCaptor.forClass(UserIdentity.class);
        verify(identityService).createOne(seeded.capture());
        assertThat(seeded.getValue().getLoginEmail()).isEqualTo("ada@acme.com");
        assertThat(seeded.getValue().getProfileId()).isEqualTo(42L);
    }
}
