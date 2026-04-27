package io.softa.framework.web.signature;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import io.softa.framework.web.signature.support.CanonicalRequest;

import static org.junit.jupiter.api.Assertions.*;

class CanonicalRequestTest {

    private static final URI URI_BASIC = URI.create("https://runtime.example.com/upgrade/upgradeMetadata");
    private static final URI URI_WITH_QUERY = URI.create("https://runtime.example.com/upgrade/upgradeMetadata?modelName=sys_model");

    @Test
    void layoutIsDeterministicNewlineSeparated() {
        byte[] bytes = CanonicalRequest.build("POST", URI_BASIC, "{}".getBytes(StandardCharsets.UTF_8),
                1_700_000_000_000L, "nonce-abc");
        String s = new String(bytes, StandardCharsets.UTF_8);
        String[] lines = s.split("\n", -1);
        // v1 header, timestamp, nonce, method, path+query, body-hash → 6 logical segments but
        // the last is not newline-terminated (no trailing \n).
        assertEquals(6, lines.length);
        assertEquals(CanonicalRequest.VERSION, lines[0]);
        assertEquals("1700000000000", lines[1]);
        assertEquals("nonce-abc", lines[2]);
        assertEquals("POST", lines[3]);
        assertEquals("/upgrade/upgradeMetadata", lines[4]);
        // SHA-256 of "{}" is lowercase hex of 64 chars.
        assertEquals(64, lines[5].length());
        assertTrue(lines[5].matches("[0-9a-f]{64}"));
    }

    @Test
    void methodIsUppercased() {
        byte[] lower = CanonicalRequest.build("post", URI_BASIC, new byte[0], 1L, "n");
        byte[] upper = CanonicalRequest.build("POST", URI_BASIC, new byte[0], 1L, "n");
        assertEquals(new String(upper, StandardCharsets.UTF_8), new String(lower, StandardCharsets.UTF_8));
    }

    @Test
    void schemeAndHostAreNotPartOfTheCanonicalString() {
        byte[] a = CanonicalRequest.build("POST", URI.create("https://a/path"), new byte[0], 1L, "n");
        byte[] b = CanonicalRequest.build("POST", URI.create("http://b/path"), new byte[0], 1L, "n");
        assertEquals(new String(a, StandardCharsets.UTF_8), new String(b, StandardCharsets.UTF_8));
    }

    @Test
    void queryStringParticipates() {
        byte[] noQuery = CanonicalRequest.build("POST", URI_BASIC, new byte[0], 1L, "n");
        byte[] withQuery = CanonicalRequest.build("POST", URI_WITH_QUERY, new byte[0], 1L, "n");
        assertNotEquals(
                new String(noQuery, StandardCharsets.UTF_8),
                new String(withQuery, StandardCharsets.UTF_8));
    }

    @Test
    void nullBodyTreatedAsEmpty() {
        byte[] nullBody = CanonicalRequest.build("POST", URI_BASIC, null, 1L, "n");
        byte[] emptyBody = CanonicalRequest.build("POST", URI_BASIC, new byte[0], 1L, "n");
        assertEquals(
                new String(nullBody, StandardCharsets.UTF_8),
                new String(emptyBody, StandardCharsets.UTF_8));
    }

    @Test
    void bodyHashChangesPerByte() {
        byte[] a = CanonicalRequest.build("POST", URI_BASIC, "a".getBytes(StandardCharsets.UTF_8), 1L, "n");
        byte[] b = CanonicalRequest.build("POST", URI_BASIC, "b".getBytes(StandardCharsets.UTF_8), 1L, "n");
        assertNotEquals(new String(a, StandardCharsets.UTF_8), new String(b, StandardCharsets.UTF_8));
    }

    @Test
    void missingPathFallsBackToRoot() {
        // URIs with an empty path hit the fallback branch; otherwise the verifier would
        // mismatch the signer for calls that resolve to "/".
        byte[] bytes = CanonicalRequest.build("GET", URI.create("https://host"), new byte[0], 1L, "n");
        String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\n", -1);
        assertEquals("/", lines[4]);
    }
}
