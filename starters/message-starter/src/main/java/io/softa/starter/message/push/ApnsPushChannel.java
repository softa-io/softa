package io.softa.starter.message.push;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.commons.lang3.StringUtils;

import io.softa.framework.base.utils.JsonUtils;

/**
 * Sends through APNs' HTTP/2 API with token authentication. The signed token is reused for 40 minutes: Apple refuses a
 * token older than an hour, and also one refreshed more often than every 20 minutes.
 */
final class ApnsPushChannel implements PushChannel {

    private static final Duration TOKEN_LIFETIME = Duration.ofMinutes(40);
    private static final String BAD_DEVICE_TOKEN = "BadDeviceToken";
    private static final String UNREGISTERED = "Unregistered";
    private static final String EXPIRED_PROVIDER_TOKEN = "ExpiredProviderToken";
    /** The bundle id or the key is wrong, not the device. */
    private static final Set<String> CONFIG_REASONS = Set.of("DeviceTokenNotForTopic", "TopicDisallowed", "MissingTopic",
            "BadTopic", "InvalidProviderToken", "MissingProviderToken", "Forbidden");

    private final PushProperties.Apns properties;
    private final Duration requestTimeout;
    private final HttpClient client;
    private final PrivateKey key;
    private final Clock clock;
    private final ReentrantLock tokenLock = new ReentrantLock();
    private volatile String token;
    private volatile Instant tokenSignedAt;

    ApnsPushChannel(PushProperties.Apns properties, Duration connectTimeout, Duration requestTimeout, Clock clock)
            throws GeneralSecurityException {
        if (StringUtils.isAnyBlank(properties.getKeyId(), properties.getTeamId(), properties.getTopic())) {
            throw new IllegalArgumentException("the key id, the team id and the topic are all required");
        }
        this.properties = properties;
        this.requestTimeout = requestTimeout;
        this.clock = clock;
        this.key = PushCredentials.privateKey(PushCredentials.plainText(properties.getPrivateKey(), "-----"), "EC");
        this.client = PushCredentials.httpClient(connectTimeout, properties.getProxyHost(), properties.getProxyPort());
        signToken();
    }

    @Override
    public PushResult send(PushRequest request) throws IOException, InterruptedException {
        boolean sandbox = "sandbox".equalsIgnoreCase(properties.getEnvironment());
        String baseUrl = sandbox ? properties.getSandboxBaseUrl() : properties.getBaseUrl();
        Answer answer = post(baseUrl, request);
        // A token from a build of the other environment reads as bad here, though it is good there.
        if (BAD_DEVICE_TOKEN.equals(answer.reason) && !sandbox && properties.isSandboxFallback()) {
            PushResult fallback = classify(post(properties.getSandboxBaseUrl(), request));
            // Refused by both environments says the configuration is in doubt, not that the token is gone.
            return fallback.outcome() == PushResult.Outcome.TOKEN_INVALID
                    ? PushResult.rejected(fallback.reason()) : fallback;
        }
        return classify(answer);
    }

    private Answer post(String baseUrl, PushRequest request) throws IOException, InterruptedException {
        HttpRequest.Builder http = HttpRequest.newBuilder(URI.create(baseUrl + "/3/device/" + request.deviceToken()))
                .timeout(requestTimeout)
                .header("authorization", "bearer " + currentToken())
                .header("apns-topic", properties.getTopic())
                .header("apns-push-type", "alert")
                .header("apns-priority", "10")
                .header("apns-expiration", String.valueOf(clock.instant().plus(request.timeToLive()).getEpochSecond()))
                .header("content-type", "application/json");
        if (StringUtils.isNotBlank(request.dedupeId())) {
            http.header("apns-collapse-id", request.dedupeId());
        }
        HttpResponse<String> response = client.send(
                http.POST(HttpRequest.BodyPublishers.ofString(payload(request))).build(),
                HttpResponse.BodyHandlers.ofString());
        return new Answer(response.statusCode(), reasonOf(response.body()), retryAfterOf(response));
    }

    private PushResult classify(Answer answer) {
        if (answer.status == 200) {
            return PushResult.sent();
        }
        String reason = answer.reason == null ? "HTTP " + answer.status : answer.reason;
        // Only these say for certain that the token is gone. BadDeviceToken is also what a token of the other APNs
        // environment gets, so a misconfigured environment would otherwise drop every iOS device at once.
        if (answer.status == 410 || UNREGISTERED.equals(answer.reason)) {
            return PushResult.tokenInvalid(reason);
        }
        if (EXPIRED_PROVIDER_TOKEN.equals(answer.reason)) {
            token = null;
            return PushResult.retry(null, reason);
        }
        if (answer.status == 403 || (answer.reason != null && CONFIG_REASONS.contains(answer.reason))) {
            return PushResult.configError(reason);
        }
        if (answer.status == 429 || answer.status >= 500) {
            return PushResult.retry(answer.retryAfter, reason);
        }
        return PushResult.rejected(reason);
    }

    private static String payload(PushRequest request) {
        Map<String, Object> alert = new LinkedHashMap<>();
        alert.put("title", request.title());
        if (request.body() != null) {
            alert.put("body", request.body());
        }
        Map<String, Object> aps = new LinkedHashMap<>();
        aps.put("alert", alert);
        aps.put("sound", "default");
        if (StringUtils.isNotBlank(request.threadId())) {
            aps.put("thread-id", request.threadId());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("aps", aps);
        if (request.data() != null && !request.data().isEmpty()) {
            payload.put("data", request.data());
        }
        return JsonUtils.objectToString(payload);
    }

    private String currentToken() throws IOException {
        if (token == null || tokenSignedAt.plus(TOKEN_LIFETIME).isBefore(clock.instant())) {
            tokenLock.lock();
            try {
                if (token == null || tokenSignedAt.plus(TOKEN_LIFETIME).isBefore(clock.instant())) {
                    signToken();
                }
            } catch (GeneralSecurityException e) {
                throw new IOException("Could not sign the APNs token", e);
            } finally {
                tokenLock.unlock();
            }
        }
        return token;
    }

    private void signToken() throws GeneralSecurityException {
        Instant now = clock.instant();
        token = PushCredentials.jwt(
                Map.of("alg", "ES256", "kid", properties.getKeyId()),
                Map.of("iss", properties.getTeamId(), "iat", now.getEpochSecond()),
                key, "SHA256withECDSAinP1363Format");
        tokenSignedAt = now;
    }

    private static String reasonOf(String body) {
        if (StringUtils.isBlank(body)) {
            return null;
        }
        try {
            Object reason = JsonUtils.stringToObject(body, Map.class).get("reason");
            return reason == null ? null : reason.toString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Duration retryAfterOf(HttpResponse<?> response) {
        return response.headers().firstValue("retry-after")
                .filter(value -> value.matches("\\d+"))
                .map(value -> Duration.ofSeconds(Long.parseLong(value)))
                .orElse(null);
    }

    private record Answer(int status, String reason, Duration retryAfter) {
    }
}
