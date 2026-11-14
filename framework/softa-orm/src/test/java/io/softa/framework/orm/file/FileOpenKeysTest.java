package io.softa.framework.orm.file;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

import io.softa.framework.orm.file.FileOpenKeys.KeyCheck;
import io.softa.framework.orm.file.FileOpenKeys.KeyStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileOpenKeysTest {

    private static final String SECRET = "test-only-file-open-key-secret-0123456789";
    private static final long FILE = 1987654321098765432L;
    private static final long USER = 42L;
    /** 22 characters of valid base64url: a signature-shaped part. */
    private static final String SIG = "AAAAAAAAAAAAAAAAAAAAAA";

    private static FileOpenKeys at(String instant) {
        return new FileOpenKeys(SECRET, "/api/hcm", Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    private static long epoch(String instant) {
        return Instant.parse(instant).getEpochSecond();
    }

    @Test
    void aUserKeyOpensForTheAccountItWasIssuedTo_andNoOther() {
        FileOpenKeys keys = at("2026-11-14T10:00:00Z");
        String key = keys.userKey(FILE, USER);

        assertThat(keys.checkUserKey(key, USER)).isEqualTo(new KeyCheck(KeyStatus.VALID, FILE));
        assertThat(keys.checkUserKey(key, 43L).status()).isEqualTo(KeyStatus.WRONG_SIGNATURE);
        assertThat(keys.checkUserKey(key, null).status()).isEqualTo(KeyStatus.WRONG_SIGNATURE);
    }

    @Test
    void aUserKeyOpensUntilUtcMidnightOfTheIssueDayPlus31Days() {
        String key = at("2026-11-14T23:59:59Z").userKey(FILE, USER);

        assertThat(key.split("\\.")[1]).isEqualTo(String.valueOf(epoch("2026-12-15T00:00:00Z")));
        assertThat(at("2026-12-14T23:59:59Z").checkUserKey(key, USER).valid()).isTrue();
        assertThat(at("2026-12-15T00:00:00Z").checkUserKey(key, USER).status()).isEqualTo(KeyStatus.EXPIRED);
    }

    @Test
    void oneDayGivesOneKey_soAPageThatRereadsItsDataKeepsItsImageAddresses() {
        assertThat(at("2026-11-14T00:00:00Z").userKey(FILE, USER))
                .isEqualTo(at("2026-11-14T23:59:59Z").userKey(FILE, USER))
                .isNotEqualTo(at("2026-11-15T00:00:00Z").userKey(FILE, USER));
    }

    @Test
    void anUnboundKeyOpensForAnyoneForAnHour() {
        String key = at("2026-11-14T10:00:00Z").unboundKey(FILE);

        assertThat(at("2026-11-14T10:59:59Z").checkUnboundKey(key)).isEqualTo(new KeyCheck(KeyStatus.VALID, FILE));
        assertThat(at("2026-11-14T11:00:00Z").checkUnboundKey(key).status()).isEqualTo(KeyStatus.EXPIRED);
    }

    @Test
    void neitherKindOpensAsTheOther() {
        FileOpenKeys keys = at("2026-11-14T10:00:00Z");

        assertThat(keys.checkUnboundKey(keys.userKey(FILE, USER)).status()).isEqualTo(KeyStatus.WRONG_SIGNATURE);
        assertThat(keys.checkUserKey(keys.unboundKey(FILE), USER).status()).isEqualTo(KeyStatus.WRONG_SIGNATURE);
    }

    @Test
    void changingAnyPartOfAKeyBreaksIt() {
        FileOpenKeys keys = at("2026-11-14T10:00:00Z");
        String[] part = keys.userKey(FILE, USER).split("\\.");
        String otherSignature = (part[2].charAt(0) == 'A' ? "B" : "A") + part[2].substring(1);

        assertThat(keys.checkUserKey((FILE + 1) + "." + part[1] + "." + part[2], USER).status())
                .isEqualTo(KeyStatus.WRONG_SIGNATURE);
        assertThat(keys.checkUserKey(part[0] + "." + (Long.parseLong(part[1]) + 86_400) + "." + part[2], USER).status())
                .isEqualTo(KeyStatus.WRONG_SIGNATURE);
        assertThat(keys.checkUserKey(part[0] + "." + part[1] + "." + otherSignature, USER).status())
                .isEqualTo(KeyStatus.WRONG_SIGNATURE);
    }

    @Test
    void anotherSecretOpensNothing() {
        String key = at("2026-11-14T10:00:00Z").userKey(FILE, USER);
        FileOpenKeys otherSecret = new FileOpenKeys(SECRET + "-rotated", "/api/hcm",
                Clock.fixed(Instant.parse("2026-11-14T10:00:00Z"), ZoneOffset.UTC));

        assertThat(otherSecret.checkUserKey(key, USER).status()).isEqualTo(KeyStatus.WRONG_SIGNATURE);
    }

    @Test
    void anythingThatIsNotAKeyIsMalformed() {
        FileOpenKeys keys = at("2026-11-14T10:00:00Z");
        String[] notKeys = {
                null, "", "abc", "1.2", "1.2.3." + SIG,
                "a.1797206400." + SIG,                      // not a number
                "1.1797206400.short",                       // signature too short
                "１.1797206400." + SIG,                 // a full-width digit Long.parseLong would take
                "99999999999999999999.1797206400." + SIG,   // 20 digits
                "9999999999999999999.1797206400." + SIG,    // 19 digits, above Long.MAX_VALUE
                "1.1797206400.AAAAAAAAAAAAAAAAAAAA+/",      // base64, not base64url
        };
        for (String notKey : notKeys) {
            assertThat(keys.checkUserKey(notKey, USER).status()).as(String.valueOf(notKey)).isEqualTo(KeyStatus.MALFORMED);
            assertThat(keys.checkUnboundKey(notKey).status()).as(String.valueOf(notKey)).isEqualTo(KeyStatus.MALFORMED);
        }
    }

    @Test
    void addressesCarryTheContextPath_andTheDownloadFlagWhenAsked() {
        FileOpenKeys keys = new FileOpenKeys(SECRET, "/api/hcm/",
                Clock.fixed(Instant.parse("2026-11-14T10:00:00Z"), ZoneOffset.UTC));
        String key = keys.userKey(FILE, USER);

        assertThat(keys.userUrl(FILE, USER, false)).isEqualTo("/api/hcm/file/open/" + key);
        assertThat(keys.userUrl(FILE, USER, true)).isEqualTo("/api/hcm/file/open/" + key + "?download=1");
        assertThat(keys.unboundUrl(FILE, false)).isEqualTo("/api/hcm/openapi/file/" + keys.unboundKey(FILE));
        assertThat(new FileOpenKeys(SECRET, null, Clock.systemUTC()).unboundUrl(FILE, true))
                .startsWith("/openapi/file/" + FILE + ".").endsWith("?download=1");
    }

    @Test
    void aKeyIsShort() {
        // 19-digit id, 10-digit expiry, 22-character signature: far from the 500-odd characters of a
        // storage link, and free of the file name.
        assertThat(at("2026-11-14T10:00:00Z").userKey(FILE, USER)).hasSize(53);
    }

    @Test
    void aBlankSecretIsRefused() {
        assertThatThrownBy(() -> new FileOpenKeys(" ", "", Clock.systemUTC())).isInstanceOf(IllegalStateException.class);
    }
}
