package io.softa.framework.base.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class HashUtilsTest {

    @Test
    void sha256Hex_matchesTheStandardVectors() {
        // FIPS 180-2 / NIST examples.
        assertThat(HashUtils.sha256Hex(""))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(HashUtils.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void sha256Hex_ofText_isTheHexOfItsUtf8Bytes() {
        String text = "工资单 Payslip é";
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);

        assertThat(HashUtils.sha256Hex(text))
                .isEqualTo(HashUtils.sha256Hex(utf8))
                .isEqualTo(HexFormat.of().formatHex(HashUtils.sha256(utf8)))
                .matches("[0-9a-f]{64}");
    }

    @Test
    void hmacSha256_matchesRfc4231TestCase2() {
        byte[] mac = HashUtils.hmacSha256("Jefe".getBytes(StandardCharsets.US_ASCII),
                "what do ya want for nothing?".getBytes(StandardCharsets.US_ASCII));

        assertThat(HexFormat.of().formatHex(mac))
                .isEqualTo("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843");
    }

    @Test
    @SuppressWarnings("deprecation")
    void computeSha256_givesWhatItAlwaysGave() {
        // Stored invitation token hashes and design checksums were written by computeSha256.
        assertThat(EncryptUtils.computeSha256("invitation-token"))
                .isEqualTo(HashUtils.sha256Hex("invitation-token"));
    }
}
