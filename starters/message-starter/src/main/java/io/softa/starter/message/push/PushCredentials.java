package io.softa.starter.message.push;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import tools.jackson.databind.json.JsonMapper;

/** What both channels need: reading a configured secret, signing a token, and building the HTTP client. */
final class PushCredentials {

    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    /**
     * A plain mapper, not the application's: that one writes a long as a string, and a token's {@code iat} and
     * {@code exp} must be numbers or the service refuses it.
     */
    private static final JsonMapper JWT_MAPPER = JsonMapper.builder().build();

    private PushCredentials() {
    }

    /** A secret given as it is, or base64-encoded so that it fits on one line of an environment file. */
    static String plainText(String configured, String plainPrefix) {
        String value = configured.trim();
        return value.startsWith(plainPrefix) ? value
                : new String(Base64.getMimeDecoder().decode(value), StandardCharsets.UTF_8);
    }

    static PrivateKey privateKey(String pem, String algorithm) throws GeneralSecurityException {
        String body = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        return KeyFactory.getInstance(algorithm).generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
    }

    /** A signed JWT: {@code signatureAlgorithm} is the JDK's name for the {@code alg} in the header. */
    static String jwt(Map<String, Object> header, Map<String, Object> claims, PrivateKey key, String signatureAlgorithm)
            throws GeneralSecurityException {
        String content = encode(header) + "." + encode(claims);
        Signature signature = Signature.getInstance(signatureAlgorithm);
        signature.initSign(key);
        signature.update(content.getBytes(StandardCharsets.UTF_8));
        return content + "." + URL_ENCODER.encodeToString(signature.sign());
    }

    private static String encode(Map<String, Object> part) {
        return URL_ENCODER.encodeToString(JWT_MAPPER.writeValueAsBytes(part));
    }

    static HttpClient httpClient(Duration connectTimeout, String proxyHost, Integer proxyPort) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(connectTimeout);
        if (StringUtils.isNotBlank(proxyHost) && proxyPort != null) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxyHost, proxyPort)));
        }
        return builder.build();
    }
}
