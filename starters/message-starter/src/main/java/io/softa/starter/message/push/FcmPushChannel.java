package io.softa.starter.message.push;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.commons.lang3.StringUtils;

import io.softa.framework.base.utils.JsonUtils;

/**
 * Sends through FCM's HTTP v1 API, exchanging the service account's key for an access token that is reused until shortly
 * before it expires. The notification's tag is its dedupe id, not a collapse key: a device offline keeps only four
 * different collapse keys, which would lose messages.
 */
final class FcmPushChannel implements PushChannel {

    private static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
    private static final String DEFAULT_TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final Duration ACCESS_TOKEN_MARGIN = Duration.ofMinutes(5);
    /**
     * After the token endpoint refused the key, sends answer CONFIG_ERROR at once for this long instead of asking
     * again each time: asking happens under one lock, so every send would queue behind it.
     */
    static final Duration REFUSED_KEY_BACKOFF = Duration.ofMinutes(1);
    /** The token belongs to another Firebase project: that device's, not the configuration's. */
    private static final String SENDER_ID_MISMATCH = "SENDER_ID_MISMATCH";

    private final PushProperties.Fcm properties;
    private final Duration requestTimeout;
    private final HttpClient client;
    private final Clock clock;
    private final String projectId;
    private final String clientEmail;
    private final String keyId;
    private final String tokenUrl;
    private final PrivateKey key;
    private final ReentrantLock tokenLock = new ReentrantLock();
    private volatile String accessToken;
    private volatile Instant accessTokenExpiresAt;
    private volatile Refusal refusal;

    FcmPushChannel(PushProperties.Fcm properties, Duration connectTimeout, Duration requestTimeout, Clock clock)
            throws GeneralSecurityException {
        Map<?, ?> account = JsonUtils.stringToObject(
                PushCredentials.plainText(properties.getServiceAccountJson(), "{"), Map.class);
        this.projectId = textOf(account, "project_id");
        this.clientEmail = textOf(account, "client_email");
        String privateKey = textOf(account, "private_key");
        if (StringUtils.isAnyBlank(projectId, clientEmail, privateKey)) {
            throw new IllegalArgumentException("the service account needs project_id, client_email and private_key");
        }
        this.keyId = textOf(account, "private_key_id");
        this.tokenUrl = StringUtils.firstNonBlank(properties.getTokenUrl(), textOf(account, "token_uri"), DEFAULT_TOKEN_URL);
        this.key = PushCredentials.privateKey(privateKey, "RSA");
        this.properties = properties;
        this.requestTimeout = requestTimeout;
        this.clock = clock;
        this.client = PushCredentials.httpClient(connectTimeout, properties.getProxyHost(), properties.getProxyPort());
    }

    @Override
    public Sent send(PushRequest request) throws IOException, InterruptedException {
        if (request.deviceToken() == null || !TOKEN.matcher(request.deviceToken()).matches()) {
            return Sent.of(PushResult.rejected("Malformed device token"));
        }
        String access;
        try {
            access = currentAccessToken();
        } catch (TokenRefused e) {
            return Sent.of(PushResult.configError(e.getMessage()));
        }
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                        URI.create(properties.getBaseUrl() + "/v1/projects/" + projectId + "/messages:send"))
                .timeout(requestTimeout)
                .header("authorization", "Bearer " + access)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload(request)))
                .build(), HttpResponse.BodyHandlers.ofString());
        return classify(response);
    }

    private Sent classify(HttpResponse<String> response) {
        int status = response.statusCode();
        if (status == 200) {
            return Sent.of(PushResult.sent());
        }
        String reason = reasonOf(response.body(), status);
        if ("UNREGISTERED".equals(reason)) {
            return Sent.of(PushResult.tokenInvalid(reason));
        }
        if (SENDER_ID_MISMATCH.equals(reason)) {
            return Sent.of(PushResult.rejected(reason));
        }
        if (status == 401) {
            accessToken = null;
            return Sent.of(PushResult.configError(reason));
        }
        if (status == 403) {
            return Sent.of(PushResult.configError(reason));
        }
        Duration retryAfter = PushCredentials.retryAfter(response);
        // 429 is usually one device's message rate: that device waits, the others go on.
        if (status == 429) {
            return Sent.of(PushResult.retry(retryAfter, reason));
        }
        if (status >= 500) {
            return new Sent(PushResult.retry(retryAfter, reason), retryAfter);
        }
        return Sent.of(PushResult.rejected(reason));
    }

    private String payload(PushRequest request) {
        Map<String, Object> notification = new LinkedHashMap<>();
        notification.put("title", request.title());
        if (request.body() != null) {
            notification.put("body", request.body());
        }
        Map<String, Object> androidNotification = new LinkedHashMap<>();
        if (StringUtils.isNotBlank(request.dedupeId())) {
            androidNotification.put("tag", request.dedupeId());
        }
        if (StringUtils.isNotBlank(properties.getAndroidChannelId())) {
            androidNotification.put("channel_id", properties.getAndroidChannelId());
        }
        Map<String, Object> android = new LinkedHashMap<>();
        android.put("priority", "HIGH");
        android.put("ttl", request.timeToLive().toSeconds() + "s");
        if (!androidNotification.isEmpty()) {
            android.put("notification", androidNotification);
        }
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("token", request.deviceToken());
        message.put("notification", notification);
        if (request.data() != null && !request.data().isEmpty()) {
            message.put("data", request.data());
        }
        message.put("android", android);
        return JsonUtils.objectToString(Map.of("message", message));
    }

    private String currentAccessToken() throws IOException, InterruptedException, TokenRefused {
        Refusal refused = refusal;
        if (refused != null && clock.instant().isBefore(refused.until())) {
            throw new TokenRefused(refused.message());
        }
        if (accessToken == null || !clock.instant().isBefore(accessTokenExpiresAt)) {
            tokenLock.lock();
            try {
                if (accessToken == null || !clock.instant().isBefore(accessTokenExpiresAt)) {
                    try {
                        exchangeKey();
                        refusal = null;
                    } catch (TokenRefused e) {
                        refusal = new Refusal(e.getMessage(), clock.instant().plus(REFUSED_KEY_BACKOFF));
                        throw e;
                    }
                }
            } finally {
                tokenLock.unlock();
            }
        }
        return accessToken;
    }

    private void exchangeKey() throws IOException, InterruptedException, TokenRefused {
        long now = clock.instant().getEpochSecond();
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "RS256");
        header.put("typ", "JWT");
        if (keyId != null) {
            header.put("kid", keyId);
        }
        String assertion;
        try {
            assertion = PushCredentials.jwt(header, Map.of("iss", clientEmail, "scope", SCOPE, "aud", tokenUrl,
                    "iat", now, "exp", now + 3600), key, "SHA256withRSA");
        } catch (GeneralSecurityException e) {
            throw new TokenRefused("Could not sign the FCM token request: " + e.getMessage());
        }
        String form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8)
                + "&assertion=" + URLEncoder.encode(assertion, StandardCharsets.UTF_8);
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(tokenUrl))
                .timeout(requestTimeout)
                .header("content-type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 500 || response.statusCode() == 429) {
            throw new IOException("The FCM token endpoint answered HTTP " + response.statusCode());
        }
        Map<?, ?> body = response.statusCode() == 200 ? JsonUtils.stringToObject(response.body(), Map.class) : Map.of();
        Object token = body.get("access_token");
        if (!(token instanceof String)) {
            throw new TokenRefused("The FCM token endpoint refused the key: HTTP " + response.statusCode());
        }
        long expiresIn = body.get("expires_in") instanceof Number seconds ? seconds.longValue() : 3600;
        accessToken = (String) token;
        accessTokenExpiresAt = clock.instant().plusSeconds(expiresIn).minus(ACCESS_TOKEN_MARGIN);
    }

    /** The most specific code the error carries: FCM's own in {@code details}, otherwise the general status. */
    private static String reasonOf(String body, int status) {
        try {
            Object error = JsonUtils.stringToObject(body, Map.class).get("error");
            if (error instanceof Map<?, ?> details) {
                if (details.get("details") instanceof List<?> list) {
                    for (Object detail : list) {
                        if (detail instanceof Map<?, ?> map && map.get("errorCode") != null) {
                            return map.get("errorCode").toString();
                        }
                    }
                }
                if (details.get("status") != null) {
                    return details.get("status").toString();
                }
            }
        } catch (RuntimeException e) {
            // Not JSON: a proxy or gateway answered. The status code still says enough.
        }
        return "HTTP " + status;
    }

    private static String textOf(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? null : value.toString();
    }

    private record Refusal(String message, Instant until) {
    }

    /** The token endpoint answered and said no: the key is wrong, and asking again will not change that. */
    private static final class TokenRefused extends Exception {
        TokenRefused(String message) {
            super(message);
        }
    }
}
