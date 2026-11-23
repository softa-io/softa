package io.softa.starter.message.sms.support.adapter;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.StringJoiner;
import java.util.UUID;

import io.softa.framework.base.security.HashUtils;

/**
 * Signs an Alibaba Cloud RPC call with the V3 algorithm, {@code ACS3-HMAC-SHA256}, for the two Aliyun SMS
 * adapters.
 * <p>
 * Both SMS operations take every parameter in the query ({@code "in": "query"} in their API metadata), so
 * the request is a {@code POST} with an empty body: the payload hash is the hash of the empty string and
 * no {@code content-type} is signed. The {@code host} header is signed but not returned — the HTTP client
 * sets it from the URI, to the same value. Send to {@link SignedRequest#uri()} as it is: the query in it
 * is the one that was signed, already encoded, and a client that encodes it again breaks the signature.
 *
 * @see <a href="https://www.alibabacloud.com/help/zh/sdk/product-overview/v3-request-structure-and-signature">V3 request structure and signature</a>
 */
final class AliyunAcs3Signer {

    static final String ALGORITHM = "ACS3-HMAC-SHA256";

    private static final DateTimeFormatter ACS_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);
    private static final String EMPTY_PAYLOAD_HASH = HashUtils.sha256Hex("");

    private AliyunAcs3Signer() {
    }

    /**
     * A signed call: where to send it, and the headers to send with it.
     *
     * @param uri     the endpoint with the encoded, signed query
     * @param headers the {@code x-acs-*} headers and {@code Authorization}
     */
    record SignedRequest(URI uri, Map<String, String> headers) {
    }

    /** Sign a call made now, with a fresh nonce. */
    static SignedRequest sign(String endpoint, String action, String version, SortedMap<String, String> params,
                              String accessKeyId, String accessKeySecret) {
        return sign(endpoint, action, version, params, accessKeyId, accessKeySecret,
                Instant.now(), UUID.randomUUID().toString().replace("-", ""));
    }

    /** Sign a call made at {@code now} with {@code nonce}: what a test fixes to check a known signature. */
    static SignedRequest sign(String endpoint, String action, String version, SortedMap<String, String> params,
                              String accessKeyId, String accessKeySecret, Instant now, String nonce) {
        URI base = URI.create(endpoint.contains("://") ? endpoint : "https://" + endpoint);
        String host = base.getPort() == -1 ? base.getHost() : base.getHost() + ":" + base.getPort();
        String query = canonicalQuery(params);

        // Sorted by name, as the canonical headers must be; host is signed, the client sends it.
        Map<String, String> signed = new LinkedHashMap<>();
        signed.put("host", host);
        signed.put("x-acs-action", action);
        signed.put("x-acs-content-sha256", EMPTY_PAYLOAD_HASH);
        signed.put("x-acs-date", ACS_DATE.format(now));
        signed.put("x-acs-signature-nonce", nonce);
        signed.put("x-acs-version", version);

        StringBuilder canonicalHeaders = new StringBuilder();
        StringJoiner signedHeaders = new StringJoiner(";");
        signed.forEach((name, value) -> {
            canonicalHeaders.append(name).append(':').append(value.trim()).append('\n');
            signedHeaders.add(name);
        });
        // RPC style: the canonical URI is "/".
        String canonicalRequest = "POST\n/\n" + query + "\n" + canonicalHeaders + "\n"
                + signedHeaders + "\n" + EMPTY_PAYLOAD_HASH;
        String stringToSign = ALGORITHM + "\n" + HashUtils.sha256Hex(canonicalRequest);
        String signature = HexFormat.of().formatHex(HashUtils.hmacSha256(
                accessKeySecret.getBytes(StandardCharsets.UTF_8), stringToSign.getBytes(StandardCharsets.UTF_8)));

        Map<String, String> headers = new LinkedHashMap<>(signed);
        headers.remove("host");
        headers.put("Authorization", ALGORITHM + " Credential=" + accessKeyId
                + ",SignedHeaders=" + signedHeaders + ",Signature=" + signature);
        String root = base.getScheme() + "://" + base.getRawAuthority() + "/";
        return new SignedRequest(URI.create(query.isEmpty() ? root : root + "?" + query), headers);
    }

    /** {@code name=value} pairs sorted by name, each side encoded per RFC 3986, joined by {@code &}. */
    static String canonicalQuery(SortedMap<String, String> params) {
        StringJoiner query = new StringJoiner("&");
        params.forEach((name, value) -> query.add(percentEncode(name) + "=" + percentEncode(value)));
        return query.toString();
    }

    /**
     * RFC 3986 encoding as Alibaba Cloud signs it: {@link URLEncoder} with {@code +} → {@code %20},
     * {@code *} → {@code %2A} and {@code %7E} → {@code ~}.
     */
    static String percentEncode(String value) {
        if (value == null) {
            return "";
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }
}
