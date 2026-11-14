package io.softa.starter.user.service.impl;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.security.PasswordUtils;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.CacheService;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.user.entity.UserIdentity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Passwords stored by earlier releases — one round of SHA-512, the salt in its own column — keep
 * signing in, and each is stored again in the current scheme the first time it does. Nothing else
 * can upgrade them: the password is known only while its owner types it.
 */
class PasswordHashUpgradeTest {

    private static final Long IDENTITY = 11L;
    private static final String PASSWORD = "Str0ngPass";

    /** SHA-512 over salt + "Str0ngPass", in hex — as an earlier release stored it. */
    private static final String LEGACY_SALT = "0123456789abcdef0123456789abcdef";
    private static final String LEGACY_HASH = "11e2c4325ec46e77f568ed57613b0f319e8e6f853bf6518db74c91d5a6ab0a4b"
            + "c9b2069f527f37b552612943c9a37c236826761f0151944bd2b52f12e06e3964";

    @SuppressWarnings("unchecked")
    private final ModelService<Long> modelService = mock(ModelService.class);
    private final UserIdentityServiceImpl identityService = spy(new UserIdentityServiceImpl(mock(CacheService.class)));

    PasswordHashUpgradeTest() {
        ReflectionTestUtils.setField(identityService, "modelService", modelService);
    }

    private static UserIdentity storedAs(String hash, String salt) {
        UserIdentity identity = new UserIdentity();
        identity.setId(IDENTITY);
        identity.setProfileId(7L);
        identity.setPassword(hash);
        identity.setPasswordSalt(salt);
        return identity;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<Map<String, Object>> fieldsCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
    }

    @Test
    void aCurrentHash_matches_withoutAWrite() {
        UserIdentity identity = storedAs(PasswordUtils.hash(PASSWORD), null);

        assertThat(identityService.matchesPassword(identity, PASSWORD)).isTrue();

        verifyNoInteractions(modelService);
    }

    @Test
    void anOldHash_matches_andIsStoredAgainInTheCurrentScheme() {
        UserIdentity identity = storedAs(LEGACY_HASH, LEGACY_SALT);
        ArgumentCaptor<Filters> where = ArgumentCaptor.forClass(Filters.class);
        ArgumentCaptor<Map<String, Object>> written = fieldsCaptor();
        when(modelService.updateByFilter(eq("UserIdentity"), where.capture(), written.capture())).thenReturn(1);

        assertThat(identityService.matchesPassword(identity, PASSWORD)).isTrue();

        // The new hash carries its own salt; the old salt column is cleared with it.
        assertThat(written.getValue()).containsOnlyKeys("password", "passwordSalt");
        assertThat(written.getValue()).containsEntry("passwordSalt", null);
        String upgraded = (String) written.getValue().get("password");
        assertThat(upgraded).startsWith("pbkdf2-sha256$");
        assertThat(PasswordUtils.matches(PASSWORD, upgraded, null)).isTrue();
        assertThat(PasswordUtils.needsRehash(upgraded)).isFalse();
        // Only while the row still holds the hash that matched.
        assertThat(where.getValue().toString())
                .contains("[\"id\",\"=\"," + IDENTITY + "]")
                .contains("[\"password\",\"=\",\"" + LEGACY_HASH + "\"]");
        // The row in hand follows, so a second check in the same request compares against the new one.
        assertThat(identity.getPassword()).isEqualTo(upgraded);
        assertThat(identity.getPasswordSalt()).isNull();
    }

    @Test
    void anOldHash_whosePasswordChangedMeanwhile_isNotOverwritten() {
        // Read before the owner changed the password on another device: the upgrade must not carry a
        // hash of the OLD password back over the new one.
        UserIdentity identity = storedAs(LEGACY_HASH, LEGACY_SALT);
        when(modelService.updateByFilter(eq("UserIdentity"), any(Filters.class), anyMap())).thenReturn(0);

        // Right for the row as it was read; the row as it is now is left as the other write left it.
        assertThat(identityService.matchesPassword(identity, PASSWORD)).isTrue();

        assertThat(identity.getPassword()).isEqualTo(LEGACY_HASH);
        assertThat(identity.getPasswordSalt()).isEqualTo(LEGACY_SALT);
    }

    @Test
    void aWrongPassword_neverMatches_andNeverWrites_inEitherScheme() {
        assertThat(identityService.matchesPassword(storedAs(LEGACY_HASH, LEGACY_SALT), "Str0ngPasS")).isFalse();
        assertThat(identityService.matchesPassword(storedAs(PasswordUtils.hash(PASSWORD), null), "Str0ngPasS"))
                .isFalse();

        verifyNoInteractions(modelService);
    }

    @Test
    void anEmptyPassword_neverMatches_andNeverWrites() {
        UserIdentity identity = storedAs(LEGACY_HASH, LEGACY_SALT);

        assertThat(identityService.matchesPassword(identity, "")).isFalse();
        assertThat(identityService.matchesPassword(identity, "   ")).isFalse();
        assertThat(identityService.matchesPassword(identity, null)).isFalse();

        verifyNoInteractions(modelService);
    }

    @Test
    void noStoredPassword_orNoPerson_neverMatches() {
        // Invited and not set yet: password sign-in is unavailable, never "any password fits".
        assertThat(identityService.matchesPassword(storedAs(null, null), PASSWORD)).isFalse();
        assertThat(identityService.matchesPassword(storedAs("", LEGACY_SALT), PASSWORD)).isFalse();
        // What the login passes for an identifier nobody holds.
        assertThat(identityService.matchesPassword(null, PASSWORD)).isFalse();

        verifyNoInteractions(modelService);
    }

    @Test
    void settingAPassword_storesTheCurrentScheme_andClearsTheOldSalt() {
        doReturn(Optional.of(storedAs(LEGACY_HASH, LEGACY_SALT))).when(identityService).getById(IDENTITY);
        ArgumentCaptor<Map<String, Object>> written = fieldsCaptor();
        doNothing().when(identityService).updateFields(eq(IDENTITY), written.capture());

        identityService.setPassword(IDENTITY, "N3w-Passw0rd");

        String stored = (String) written.getValue().get("password");
        assertThat(stored).startsWith("pbkdf2-sha256$");
        assertThat(PasswordUtils.matches("N3w-Passw0rd", stored, null)).isTrue();
        assertThat(written.getValue()).containsEntry("passwordSalt", null);
    }
}
