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
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.commons.lang3.StringUtils;

import io.softa.framework.base.utils.JsonUtils;

/**
 * Sends through APNs' HTTP/2 API with token authentication. The signed token is reused for 40 minutes: Apple refuses a
 * token older than an hour, and also one refreshed more often than every 20 minutes.
 */
final class ApnsPushChannel implements PushChannel {

    static final Duration TOKEN_LIFETIME = Duration.ofMinutes(40);
    /** Apple answers {@code TooManyProviderTokenUpdates} to a token re-signed sooner than this. */
    static final Duration MIN_RESIGN_INTERVAL = Duration.ofMinutes(20);
    private static final String BAD_DEVICE_TOKEN = "BadDeviceToken";
    private static final String UNREGISTERED = "Unregistered";
    private static final String EXPIRED_PROVIDER_TOKEN = "ExpiredProviderToken";
    /** The bundle id or the key is wrong, not the device. */
    private static final Set<String> CONFIG_REASONS = Set.of("DeviceTokenNotForTopic", "TopicDisallowed", "MissingTopic",
            "BadTopic", "InvalidProviderToken", "MissingProviderToken", "Forbidden", "TooManyProviderTokenUpdates");

    private final PushProperties.Apns properties;
    private final Duration requestTimeout;
    private final HttpClient client;
    private final PrivateKey key;
    private final Clock clock;
    private final ReentrantLock signLock = new ReentrantLock();
    /** The token and when it was signed, replaced as one: a reader never sees a token without its time. */
    private final AtomicReference<SignedToken> token = new AtomicReference<>();

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
        token.set(sign());
    }

    @Override
    public PushResult send(PushRequest request) throws IOException, InterruptedException {
        if (request.deviceToken() == null || !TOKEN.matcher(request.deviceToken()).matches()) {
            return PushResult.rejected("Malformed device token");
        }
        boolean sandbox = "sandbox".equalsIgnoreCase(properties.getEnvironment());
        String baseUrl = sandbox ? properties.getSandboxBaseUrl() : properties.getBaseUrl();
        Answer answer = post(baseUrl, request);
        // A token from a build of the other environment reads as bad here, though it is good there.
        if (BAD_DEVICE_TOKEN.equals(answer.reason) && !sandbox && properties.isSandboxFallback()) {
            Answer fallback;
            try {
                fallback = post(properties.getSandboxBaseUrl(), request);
            } catch (IOException e) {
                // Production answered, so its network is fine: a sandbox that cannot be reached must not count
                // towards pausing production.
                return PushResult.failed("Sandbox fallback: " + GuardedChannel.describe(e));
            }
            PushResult classified = classify(fallback);
            // Refused by both environments says the configuration is in doubt, not that the token is gone.
            return classified.outcome() == PushResult.Outcome.TOKEN_INVALID
                    ? PushResult.rejected(classified.reason()) : classified;
        }
        return classify(answer);
    }

    private Answer post(String baseUrl, PushRequest request) throws IOException, InterruptedException {
        SignedToken used = currentToken();
        HttpRequest.Builder http = HttpRequest.newBuilder(URI.create(baseUrl + "/3/device/" + request.deviceToken()))
                .timeout(requestTimeout)
                .header("authorization", "bearer " + used.value())
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
        return new Answer(response.statusCode(), reasonOf(response.body()), used);
    }

    private PushResult classify(Answer answer) throws ServiceTrouble {
        if (answer.status == 200) {
            return PushResult.sent();
        }
        String reason = "HTTP " + answer.status + (answer.reason == null ? "" : " " + answer.reason);
        // Only these say for certain that the token is gone. BadDeviceToken is also what a token of the other APNs
        // environment gets, so a misconfigured environment would otherwise drop every iOS device at once.
        if (answer.status == 410 || UNREGISTERED.equals(answer.reason)) {
            return PushResult.tokenInvalid(reason);
        }
        if (EXPIRED_PROVIDER_TOKEN.equals(answer.reason)) {
            return expired(answer.used, reason);
        }
        if (answer.status == 403 || (answer.reason != null && CONFIG_REASONS.contains(answer.reason))) {
            return PushResult.configError(reason);
        }
        // 429 is TooManyRequests for this one device: it fails, the others go on.
        if (answer.status == 429) {
            return PushResult.failed(reason);
        }
        if (answer.status >= 500) {
            throw new ServiceTrouble(reason);
        }
        return PushResult.rejected(reason);
    }

    /**
     * Apple calls the token too old. Signed long enough ago, it is dropped and the next send signs a new one; signed
     * recently, re-signing would only earn {@code TooManyProviderTokenUpdates}, so the clocks disagree.
     */
    private PushResult expired(SignedToken used, String reason) {
        Duration age = Duration.between(used.signedAt(), clock.instant());
        if (age.compareTo(MIN_RESIGN_INTERVAL) < 0) {
            return PushResult.configError(reason + " for a token signed " + age.toMinutes()
                    + " minutes ago: check the server's clock");
        }
        // Only the token this send used: another send may have signed a fresh one already. The next send signs anew.
        token.compareAndSet(used, null);
        return PushResult.failed(reason);
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

    private SignedToken currentToken() throws IOException {
        SignedToken current = token.get();
        if (usable(current)) {
            return current;
        }
        signLock.lock();
        try {
            current = token.get();
            if (!usable(current)) {
                current = sign();
                token.set(current);
            }
            return current;
        } catch (GeneralSecurityException e) {
            throw new IOException("Could not sign the APNs token", e);
        } finally {
            signLock.unlock();
        }
    }

    private boolean usable(SignedToken current) {
        return current != null && current.signedAt().plus(TOKEN_LIFETIME).isAfter(clock.instant());
    }

    private SignedToken sign() throws GeneralSecurityException {
        Instant now = clock.instant();
        String value = PushCredentials.jwt(
                Map.of("alg", "ES256", "kid", properties.getKeyId()),
                Map.of("iss", properties.getTeamId(), "iat", now.getEpochSecond()),
                key, "SHA256withECDSAinP1363Format");
        return new SignedToken(value, now);
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

    private record SignedToken(String value, Instant signedAt) {
    }

    private record Answer(int status, String reason, SignedToken used) {
    }
}
