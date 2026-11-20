package io.softa.starter.message.push;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import io.softa.starter.message.push.PushResult.Outcome;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Both channels against a local stand-in for APNs and FCM, over real HTTP. */
class PushChannelsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-07T00:00:00Z"), ZoneOffset.UTC);

    private record Seen(String path, Headers headers, String body) {
    }

    private record Answer(int status, String body, Map<String, String> headers) {
        static Answer of(int status, String body) {
            return new Answer(status, body, Map.of());
        }
    }

    private HttpServer server;
    private String baseUrl;
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private volatile Function<Seen, Answer> answers = request -> Answer.of(200, "{}");

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Seen request = new Seen(exchange.getRequestURI().toString(), exchange.getRequestHeaders(), body);
            seen.add(request);
            Answer answer = answers.apply(request);
            answer.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
            byte[] bytes = answer.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(answer.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    // ---- APNs ----

    private KeyPair apnsKey;

    private DefaultPushSender apns(java.util.function.Consumer<PushProperties.Apns> tweak) throws Exception {
        return apns(tweak, CLOCK);
    }

    private DefaultPushSender apns(java.util.function.Consumer<PushProperties.Apns> tweak, Clock clock) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        apnsKey = generator.generateKeyPair();
        PushProperties properties = new PushProperties();
        PushProperties.Apns apns = properties.getApns();
        apns.setPrivateKey(pem("PRIVATE KEY", apnsKey.getPrivate().getEncoded()));
        apns.setKeyId("KEY123");
        apns.setTeamId("TEAM123");
        apns.setTopic("com.example.app");
        apns.setBaseUrl(baseUrl + "/prod");
        apns.setSandboxBaseUrl(baseUrl + "/sandbox");
        tweak.accept(apns);
        return new DefaultPushSender(properties, clock);
    }

    @Test
    void apnsRequestCarriesWhatTheAppAndAppleExpect() throws Exception {
        PushResult result = apns(a -> { }).send(request(PushPlatform.IOS, "body"));

        assertThat(result.outcome()).isEqualTo(Outcome.SENT);
        Seen request = seen.getFirst();
        assertThat(request.path()).isEqualTo("/prod/3/device/token-1");
        assertThat(request.headers().getFirst("apns-topic")).isEqualTo("com.example.app");
        assertThat(request.headers().getFirst("apns-push-type")).isEqualTo("alert");
        assertThat(request.headers().getFirst("apns-priority")).isEqualTo("10");
        assertThat(request.headers().getFirst("apns-collapse-id")).isEqualTo("n-1");
        assertThat(request.headers().getFirst("apns-expiration"))
                .isEqualTo(String.valueOf(CLOCK.instant().plus(Duration.ofHours(24)).getEpochSecond()));
        Map<?, ?> payload = JSON.readValue(request.body(), Map.class);
        Map<?, ?> aps = (Map<?, ?>) payload.get("aps");
        assertThat(aps.get("alert")).isEqualTo(Map.of("title", "title", "body", "body"));
        assertThat(aps.get("sound")).isEqualTo("default");
        assertThat(aps.get("thread-id")).isEqualTo("APPROVAL");
        assertThat(payload.get("data")).isEqualTo(Map.of("type", "APPROVAL", "targetId", "9"));
    }

    @Test
    void apnsTokenIsSignedWithNumericClaimsAndReused() throws Exception {
        DefaultPushSender sender = apns(a -> { });
        sender.send(request(PushPlatform.IOS, null));
        sender.send(request(PushPlatform.IOS, null));

        String first = seen.get(0).headers().getFirst("authorization").substring("bearer ".length());
        assertThat(seen.get(1).headers().getFirst("authorization")).endsWith(first);
        String[] parts = first.split("\\.");
        Map<?, ?> header = JSON.readValue(Base64.getUrlDecoder().decode(parts[0]), Map.class);
        Map<?, ?> claims = JSON.readValue(Base64.getUrlDecoder().decode(parts[1]), Map.class);
        assertThat(header.get("alg")).isEqualTo("ES256");
        assertThat(header.get("kid")).isEqualTo("KEY123");
        assertThat(claims.get("iss")).isEqualTo("TEAM123");
        assertThat(claims.get("iat")).isInstanceOf(Number.class);
        Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
        verifier.initVerify((ECPublicKey) apnsKey.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
        assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
    }

    @Test
    void aBodyOfNullLeavesTheBodyOut() throws Exception {
        apns(a -> { }).send(request(PushPlatform.IOS, null));

        Map<?, ?> aps = (Map<?, ?>) JSON.readValue(seen.getFirst().body(), Map.class).get("aps");
        assertThat(aps.get("alert")).isEqualTo(Map.of("title", "title"));
    }

    @Test
    void apnsAnswersAreClassified() throws Exception {
        DefaultPushSender sender = apns(a -> { });
        assertApns(sender, 410, "{\"reason\":\"Unregistered\"}", Outcome.TOKEN_INVALID, "HTTP 410 Unregistered");
        assertApns(sender, 400, "{\"reason\":\"BadDeviceToken\"}", Outcome.REJECTED, "HTTP 400 BadDeviceToken");
        assertApns(sender, 400, "{\"reason\":\"DeviceTokenNotForTopic\"}", Outcome.CONFIG_ERROR,
                "HTTP 400 DeviceTokenNotForTopic");
        assertApns(sender, 403, "{\"reason\":\"InvalidProviderToken\"}", Outcome.CONFIG_ERROR,
                "HTTP 403 InvalidProviderToken");
        assertApns(sender, 400, "{\"reason\":\"PayloadEmpty\"}", Outcome.REJECTED, "HTTP 400 PayloadEmpty");
        assertApns(sender, 429, "{\"reason\":\"TooManyRequests\"}", Outcome.FAILED, "HTTP 429 TooManyRequests");
        assertApns(sender, 500, "{\"reason\":\"InternalServerError\"}", Outcome.FAILED, "HTTP 500 InternalServerError");
        assertApns(sender, 503, "", Outcome.FAILED, "HTTP 503");
    }

    private void assertApns(DefaultPushSender sender, int status, String body, Outcome outcome, String reason) {
        answers = request -> Answer.of(status, body);
        PushResult result = sender.send(request(PushPlatform.IOS, null));
        assertThat(result.outcome()).isEqualTo(outcome);
        assertThat(result.reason()).isEqualTo(reason);
    }

    @Test
    void anExpiredProviderTokenSignedLongEnoughAgoIsResigned() throws Exception {
        MutableClock clock = new MutableClock();
        DefaultPushSender sender = apns(a -> { }, clock);
        clock.advance(Duration.ofMinutes(25));
        answers = request -> Answer.of(403, "{\"reason\":\"ExpiredProviderToken\"}");

        PushResult expired = sender.send(request(PushPlatform.IOS, null));
        assertThat(expired.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(expired.reason()).isEqualTo("HTTP 403 ExpiredProviderToken");

        answers = request -> Answer.of(200, "");
        sender.send(request(PushPlatform.IOS, null));
        assertThat(seen.get(1).headers().getFirst("authorization"))
                .isNotEqualTo(seen.get(0).headers().getFirst("authorization"));
    }

    @Test
    void anExpiredProviderTokenJustSignedIsAClockProblem_notAResign() throws Exception {
        MutableClock clock = new MutableClock();
        DefaultPushSender sender = apns(a -> { }, clock);
        clock.advance(Duration.ofMinutes(5));
        answers = request -> Answer.of(403, "{\"reason\":\"ExpiredProviderToken\"}");

        PushResult result = sender.send(request(PushPlatform.IOS, null));

        assertThat(result.outcome()).isEqualTo(Outcome.CONFIG_ERROR);
        assertThat(result.reason()).contains("clock");
        answers = request -> Answer.of(200, "");
        sender.send(request(PushPlatform.IOS, null));
        assertThat(seen.get(1).headers().getFirst("authorization"))
                .isEqualTo(seen.get(0).headers().getFirst("authorization"));
    }

    @Test
    void theApnsTokenIsResignedAfterFortyMinutes() throws Exception {
        MutableClock clock = new MutableClock();
        DefaultPushSender sender = apns(a -> { }, clock);
        sender.send(request(PushPlatform.IOS, null));
        clock.advance(Duration.ofMinutes(39));
        sender.send(request(PushPlatform.IOS, null));
        clock.advance(Duration.ofMinutes(2));
        sender.send(request(PushPlatform.IOS, null));

        List<String> tokens = seen.stream().map(request -> request.headers().getFirst("authorization")).toList();
        assertThat(tokens.get(1)).isEqualTo(tokens.get(0));
        assertThat(tokens.get(2)).isNotEqualTo(tokens.get(1));
    }

    @Test
    void sendsRacingAnExpiringTokenNeverGoOutWithoutOne() throws Exception {
        MutableClock clock = new MutableClock();
        DefaultPushSender sender = apns(a -> { }, clock);
        clock.advance(Duration.ofMinutes(41));
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            threads.add(Thread.ofVirtual().start(() -> sender.send(request(PushPlatform.IOS, null))));
        }
        for (Thread thread : threads) {
            thread.join();
        }

        assertThat(seen).hasSize(16);
        assertThat(seen).allSatisfy(request -> assertThat(request.headers().getFirst("authorization"))
                .startsWith("bearer ey").doesNotContain("null"));
        assertThat(seen.stream().map(request -> request.headers().getFirst("authorization")).distinct()).hasSize(1);
    }

    @Test
    void sandboxFallbackRescuesATokenOfTheOtherEnvironment() throws Exception {
        answers = request -> request.path().startsWith("/prod")
                ? Answer.of(400, "{\"reason\":\"BadDeviceToken\"}") : Answer.of(200, "");

        PushResult result = apns(a -> a.setSandboxFallback(true)).send(request(PushPlatform.IOS, null));

        assertThat(result.outcome()).isEqualTo(Outcome.SENT);
        assertThat(seen).extracting(Seen::path).containsExactly("/prod/3/device/token-1", "/sandbox/3/device/token-1");
    }

    @Test
    void anUnreachableSandboxIsAFailure_thatDoesNotCountAgainstProduction() throws Exception {
        DefaultPushSender sender = apns(a -> {
            a.setSandboxFallback(true);
            a.setSandboxBaseUrl("http://127.0.0.1:1");
        });
        answers = request -> Answer.of(400, "{\"reason\":\"BadDeviceToken\"}");

        for (int i = 0; i < GuardedChannel.FAILURES_BEFORE_PAUSE + 2; i++) {
            PushResult result = sender.send(request(PushPlatform.IOS, null));
            assertThat(result.outcome()).isEqualTo(Outcome.FAILED);
            assertThat(result.reason()).startsWith("Sandbox fallback: ConnectException");
        }
        // Never paused: production was asked every time.
        assertThat(seen).hasSize(GuardedChannel.FAILURES_BEFORE_PAUSE + 2);
    }

    @Test
    void withoutFallbackABadTokenIsNotSentTwice() throws Exception {
        answers = request -> Answer.of(400, "{\"reason\":\"BadDeviceToken\"}");

        assertThat(apns(a -> { }).send(request(PushPlatform.IOS, null)).outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(seen).hasSize(1);
    }

    @Test
    void aTokenRefusedByBothEnvironmentsIsNeverCalledGone() throws Exception {
        answers = request -> request.path().startsWith("/prod")
                ? Answer.of(400, "{\"reason\":\"BadDeviceToken\"}") : Answer.of(410, "{\"reason\":\"Unregistered\"}");

        PushResult result = apns(a -> a.setSandboxFallback(true)).send(request(PushPlatform.IOS, null));

        assertThat(result.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(seen).hasSize(2);
    }

    @Test
    void theSandboxEnvironmentGoesStraightToSandbox() throws Exception {
        apns(a -> a.setEnvironment("sandbox")).send(request(PushPlatform.IOS, null));

        assertThat(seen.getFirst().path()).startsWith("/sandbox/");
    }

    @Test
    void aServiceInTroubleCountsTowardsPausingThePlatform_retryAfterIgnored() throws Exception {
        DefaultPushSender sender = apns(a -> { });
        answers = request -> new Answer(503, "{\"reason\":\"ServiceUnavailable\"}", Map.of("Retry-After", "120"));

        for (int i = 0; i < GuardedChannel.FAILURES_BEFORE_PAUSE; i++) {
            PushResult failed = sender.send(request(PushPlatform.IOS, null));
            assertThat(failed.outcome()).isEqualTo(Outcome.FAILED);
            assertThat(failed.reason()).isEqualTo("HTTP 503 ServiceUnavailable");
        }

        // Paused: the next send never reaches the service, and says what paused it.
        PushResult paused = sender.send(request(PushPlatform.IOS, null));
        assertThat(paused.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(paused.reason()).startsWith("Paused until").endsWith("after: HTTP 503 ServiceUnavailable");
        assertThat(seen).hasSize(GuardedChannel.FAILURES_BEFORE_PAUSE);
    }

    @Test
    void oneDevicesRateLimitPausesOnlyThatDevice() throws Exception {
        DefaultPushSender sender = apns(a -> { });
        answers = request -> new Answer(429, "{\"reason\":\"TooManyRequests\"}", Map.of("Retry-After", "120"));

        for (int i = 0; i < GuardedChannel.FAILURES_BEFORE_PAUSE + 2; i++) {
            assertThat(sender.send(request(PushPlatform.IOS, null)).outcome()).isEqualTo(Outcome.FAILED);
        }

        answers = request -> Answer.of(200, "");
        assertThat(sender.send(request(PushPlatform.IOS, null)).outcome()).isEqualTo(Outcome.SENT);
        assertThat(seen).hasSize(GuardedChannel.FAILURES_BEFORE_PAUSE + 3);
    }

    @Test
    void fcmRateLimitForOneDeviceDoesNotPauseAndroid() throws Exception {
        DefaultPushSender sender = fcm();
        answers = fcmAnswering(new Answer(429, fcmError("RESOURCE_EXHAUSTED", "QUOTA_EXCEEDED"), Map.of("Retry-After", "30")));
        PushResult limited = sender.send(request(PushPlatform.ANDROID, null));
        assertThat(limited.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(limited.reason()).isEqualTo("HTTP 429 QUOTA_EXCEEDED");

        answers = fcmAnswering(Answer.of(200, "{}"));
        assertThat(sender.send(request(PushPlatform.ANDROID, null)).outcome()).isEqualTo(Outcome.SENT);
    }

    @Test
    void aProxyCarriesTheRequest() throws Exception {
        DefaultPushSender sender = apns(a -> {
            a.setBaseUrl("http://apns.invalid");
            a.setProxyHost("127.0.0.1");
            a.setProxyPort(server.getAddress().getPort());
        });

        assertThat(sender.send(request(PushPlatform.IOS, null)).outcome()).isEqualTo(Outcome.SENT);
        assertThat(seen.getFirst().headers().getFirst("Host")).isEqualTo("apns.invalid");
    }

    // ---- FCM ----

    private DefaultPushSender fcm() throws Exception {
        return fcm(CLOCK);
    }

    private DefaultPushSender fcm(Clock clock) throws Exception {
        return fcm(clock, baseUrl + "/token");
    }

    private DefaultPushSender fcm(Clock clock, String tokenUri) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair key = generator.generateKeyPair();
        String account = JSON.writeValueAsString(Map.of("project_id", "demo", "client_email", "push@demo.iam",
                "private_key_id", "kid-1", "private_key", pem("PRIVATE KEY", key.getPrivate().getEncoded()),
                "token_uri", tokenUri));
        PushProperties properties = new PushProperties();
        // Base64, the form it takes in an environment file.
        properties.getFcm().setServiceAccountJson(Base64.getEncoder().encodeToString(account.getBytes(StandardCharsets.UTF_8)));
        properties.getFcm().setBaseUrl(baseUrl);
        properties.getFcm().setAndroidChannelId("approvals");
        return new DefaultPushSender(properties, clock);
    }

    private Function<Seen, Answer> fcmAnswering(Answer send) {
        return request -> request.path().equals("/token")
                ? Answer.of(200, "{\"access_token\":\"access-1\",\"expires_in\":3600}") : send;
    }

    @Test
    void fcmRequestCarriesWhatTheAppAndGoogleExpect() throws Exception {
        answers = fcmAnswering(Answer.of(200, "{\"name\":\"m1\"}"));

        PushResult result = fcm().send(request(PushPlatform.ANDROID, "body"));

        assertThat(result.outcome()).isEqualTo(Outcome.SENT);
        assertThat(seen.get(0).path()).isEqualTo("/token");
        assertThat(seen.get(0).body()).contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer");
        String assertion = seen.get(0).body().split("assertion=")[1];
        Map<?, ?> claims = JSON.readValue(Base64.getUrlDecoder().decode(assertion.split("\\.")[1]), Map.class);
        assertThat(claims.get("iss")).isEqualTo("push@demo.iam");
        assertThat(claims.get("scope")).isEqualTo("https://www.googleapis.com/auth/firebase.messaging");
        assertThat(claims.get("iat")).isInstanceOf(Number.class);
        assertThat(claims.get("exp")).isInstanceOf(Number.class);

        Seen send = seen.get(1);
        assertThat(send.path()).isEqualTo("/v1/projects/demo/messages:send");
        assertThat(send.headers().getFirst("authorization")).isEqualTo("Bearer access-1");
        Map<?, ?> message = (Map<?, ?>) JSON.readValue(send.body(), Map.class).get("message");
        assertThat(message.get("token")).isEqualTo("token-1");
        assertThat(message.get("notification")).isEqualTo(Map.of("title", "title", "body", "body"));
        assertThat(message.get("data")).isEqualTo(Map.of("type", "APPROVAL", "targetId", "9"));
        Map<?, ?> android = (Map<?, ?>) message.get("android");
        assertThat(android.get("priority")).isEqualTo("HIGH");
        assertThat(android.get("ttl")).isEqualTo("86400s");
        assertThat(android.get("notification")).isEqualTo(Map.of("tag", "n-1", "channel_id", "approvals"));
        assertThat(message.containsKey("collapse_key")).isFalse();
    }

    @Test
    void fcmAccessTokenIsExchangedOnce() throws Exception {
        answers = fcmAnswering(Answer.of(200, "{}"));
        DefaultPushSender sender = fcm();

        sender.send(request(PushPlatform.ANDROID, null));
        sender.send(request(PushPlatform.ANDROID, null));

        assertThat(seen).extracting(Seen::path).containsExactly("/token", "/v1/projects/demo/messages:send",
                "/v1/projects/demo/messages:send");
    }

    @Test
    void fcmAnswersAreClassified() throws Exception {
        DefaultPushSender sender = fcm();
        assertFcm(sender, 404, fcmError("NOT_FOUND", "UNREGISTERED"), Outcome.TOKEN_INVALID, "HTTP 404 UNREGISTERED");
        assertFcm(sender, 404, "<html>not found</html>", Outcome.REJECTED, "HTTP 404");
        assertFcm(sender, 403, fcmError("PERMISSION_DENIED", "SENDER_ID_MISMATCH"), Outcome.REJECTED,
                "HTTP 403 SENDER_ID_MISMATCH");
        assertFcm(sender, 403, fcmError("PERMISSION_DENIED", "PERMISSION_DENIED"), Outcome.CONFIG_ERROR,
                "HTTP 403 PERMISSION_DENIED");
        assertFcm(sender, 400, fcmError("INVALID_ARGUMENT", "INVALID_ARGUMENT"), Outcome.REJECTED,
                "HTTP 400 INVALID_ARGUMENT");
        assertFcm(sender, 500, fcmError("INTERNAL", "INTERNAL"), Outcome.FAILED, "HTTP 500 INTERNAL");
        assertFcm(sender, 503, "<html>gateway</html>", Outcome.FAILED, "HTTP 503");
        assertFcm(sender, 401, fcmError("UNAUTHENTICATED", "THIRD_PARTY_AUTH_ERROR"), Outcome.CONFIG_ERROR,
                "HTTP 401 THIRD_PARTY_AUTH_ERROR");
    }

    private void assertFcm(DefaultPushSender sender, int status, String body, Outcome outcome, String reason) {
        answers = fcmAnswering(Answer.of(status, body));
        PushResult result = sender.send(request(PushPlatform.ANDROID, null));
        assertThat(result.outcome()).isEqualTo(outcome);
        assertThat(result.reason()).isEqualTo(reason);
    }

    private static String fcmError(String status, String errorCode) {
        return "{\"error\":{\"code\":1,\"status\":\"" + status + "\",\"details\":[{\"@type\":\"t\",\"errorCode\":\""
                + errorCode + "\"}]}}";
    }

    @Test
    void aRefusedKeyIsAConfigurationError_notATokenProblem() throws Exception {
        answers = request -> Answer.of(400, "{\"error\":\"invalid_grant\"}");

        assertThat(fcm().send(request(PushPlatform.ANDROID, null)).outcome()).isEqualTo(Outcome.CONFIG_ERROR);
    }

    @Test
    void aRefusedKeyIsNotAskedAgainForAMinute() throws Exception {
        MutableClock clock = new MutableClock();
        DefaultPushSender sender = fcm(clock);
        answers = request -> Answer.of(400, "{\"error\":\"invalid_grant\"}");

        for (int i = 0; i < 5; i++) {
            assertThat(sender.send(request(PushPlatform.ANDROID, null)).outcome()).isEqualTo(Outcome.CONFIG_ERROR);
        }
        assertThat(seen).hasSize(1);

        clock.advance(FcmPushChannel.REFUSED_KEY_BACKOFF.plusSeconds(1));
        answers = fcmAnswering(Answer.of(200, "{}"));
        assertThat(sender.send(request(PushPlatform.ANDROID, null)).outcome()).isEqualTo(Outcome.SENT);
    }

    @Test
    void anExpiredAccessTokenIsExchangedAgain() throws Exception {
        MutableClock clock = new MutableClock();
        DefaultPushSender sender = fcm(clock);
        answers = fcmAnswering(Answer.of(200, "{}"));

        sender.send(request(PushPlatform.ANDROID, null));
        clock.advance(Duration.ofMinutes(54));
        sender.send(request(PushPlatform.ANDROID, null));
        clock.advance(Duration.ofMinutes(2));
        sender.send(request(PushPlatform.ANDROID, null));

        assertThat(seen).extracting(Seen::path).containsExactly("/token", "/v1/projects/demo/messages:send",
                "/v1/projects/demo/messages:send", "/token", "/v1/projects/demo/messages:send");
    }

    @Test
    void aTokenEndpointThatIsDownFailsTheSend() throws Exception {
        answers = request -> Answer.of(503, "");

        PushResult result = fcm().send(request(PushPlatform.ANDROID, null));
        assertThat(result.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(result.reason()).isEqualTo("IOException: HTTP 503");
    }

    @Test
    void anUnreachableTokenEndpointIsNotAskedAgainForAMinute_byAnySend() throws Exception {
        DefaultPushSender sender = fcm(new MutableClock(), "http://127.0.0.1:1/token");

        PushResult first = sender.send(request(PushPlatform.ANDROID, null));
        assertThat(first.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(first.reason()).startsWith("ConnectException");
        // Every send of the next minute fails at once with the same cause, none asks the endpoint again.
        for (int i = 0; i < 5; i++) {
            PushResult next = sender.send(request(PushPlatform.ANDROID, null));
            assertThat(next.outcome()).isEqualTo(Outcome.FAILED);
            assertThat(next.reason()).contains("could not be reached");
        }
        assertThat(seen).isEmpty();
    }

    // ---- enabling ----

    @Test
    void withNothingConfiguredNothingIsAvailable() {
        DefaultPushSender sender = new DefaultPushSender(new PushProperties(), CLOCK);

        assertThat(sender.anyAvailable()).isFalse();
        assertThatThrownBy(() -> sender.send(request(PushPlatform.IOS, null))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void eachPlatformIsEnabledOnItsOwn() throws Exception {
        DefaultPushSender fcmOnly = fcm();
        assertThat(fcmOnly.available(PushPlatform.ANDROID)).isTrue();
        assertThat(fcmOnly.available(PushPlatform.IOS)).isFalse();

        DefaultPushSender apnsOnly = apns(a -> { });
        assertThat(apnsOnly.available(PushPlatform.IOS)).isTrue();
        assertThat(apnsOnly.available(PushPlatform.ANDROID)).isFalse();
    }

    @Test
    void anUnusableKeyLeavesThePlatformOff_andDoesNotStopStartup() {
        PushProperties properties = new PushProperties();
        properties.getApns().setPrivateKey("-----BEGIN PRIVATE KEY-----\nnot a key\n-----END PRIVATE KEY-----");
        properties.getApns().setKeyId("K");
        properties.getApns().setTeamId("T");
        properties.getApns().setTopic("t");
        properties.getFcm().setServiceAccountJson("{\"project_id\":\"x\"}");

        DefaultPushSender sender = new DefaultPushSender(properties, CLOCK);

        assertThat(sender.anyAvailable()).isFalse();
    }

    @Test
    void anIncompleteApnsConfigurationLeavesItOff() throws Exception {
        assertThat(apns(a -> a.setTopic(" ")).available(PushPlatform.IOS)).isFalse();
    }

    @Test
    void logOnlySendsNothing_onEitherPlatform() {
        PushProperties properties = new PushProperties();
        properties.setLogOnly(true);
        DefaultPushSender sender = new DefaultPushSender(properties, CLOCK);

        assertThat(sender.available(PushPlatform.IOS)).isTrue();
        assertThat(sender.available(PushPlatform.ANDROID)).isTrue();
        assertThat(sender.send(request(PushPlatform.ANDROID, "b")).outcome()).isEqualTo(Outcome.SENT);
        assertThat(seen).isEmpty();
    }

    // ---- network trouble and length ----

    @Test
    void anUnreachableServiceIsAFailure_andPausesAfterTenFailures() throws Exception {
        server.stop(0);
        int[] attempts = {0};
        PushChannel unreachable = request -> {
            attempts[0]++;
            throw new java.net.ConnectException("refused");
        };
        GuardedChannel channel = new GuardedChannel(PushPlatform.ANDROID, unreachable, 10, CLOCK);

        List<PushResult> results = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            results.add(channel.send(request(PushPlatform.ANDROID, null)));
        }

        assertThat(results).allMatch(result -> result.outcome() == Outcome.FAILED);
        assertThat(results.getFirst().reason()).isEqualTo("ConnectException: refused");
        assertThat(attempts[0]).isEqualTo(GuardedChannel.FAILURES_BEFORE_PAUSE);
    }

    @Test
    void oneAnswerResetsTheFailureCount() {
        int[] calls = {0};
        PushChannel flaky = request -> {
            if (++calls[0] % 9 == 0) {
                return PushResult.sent();
            }
            throw new java.net.ConnectException("refused");
        };
        GuardedChannel channel = new GuardedChannel(PushPlatform.ANDROID, flaky, 10, CLOCK);

        for (int i = 0; i < 40; i++) {
            channel.send(request(PushPlatform.ANDROID, null));
        }

        assertThat(calls[0]).isEqualTo(40);
    }

    @Test
    void aLongTitleIsCutOnACharacterBoundary() {
        String cut = DefaultPushSender.fit("意见".repeat(1000), 1000);

        assertThat(cut.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(1000);
        assertThat(cut).endsWith("…").doesNotContain("�");
        assertThat(DefaultPushSender.fit("short", 1000)).isEqualTo("short");
        assertThat(DefaultPushSender.fit(null, 1000)).isNull();
    }

    @Test
    void aBodyIsMadeOneLine_andCutToWhatALockScreenShows() {
        assertThat(DefaultPushSender.trim("Rejected:\n\n  wrong   amount\r\n", 100)).isEqualTo("Rejected: wrong amount");
        String cut = DefaultPushSender.trim("意见😀".repeat(100), 100);
        assertThat(cut.codePointCount(0, cut.length())).isEqualTo(100);
        assertThat(cut).endsWith("…").doesNotContain("�");
        assertThat(DefaultPushSender.trim("x".repeat(100), 100)).isEqualTo("x".repeat(100));
        assertThat(DefaultPushSender.trim(" \n ", 100)).isNull();
        assertThat(DefaultPushSender.trim(null, 100)).isNull();
    }

    @Test
    void aLongBodyReachesTheServiceCut() throws Exception {
        apns(a -> { }).send(request(PushPlatform.IOS, "x".repeat(5000)));

        Map<?, ?> alert = (Map<?, ?>) ((Map<?, ?>) JSON.readValue(seen.getFirst().body(), Map.class).get("aps")).get("alert");
        assertThat(alert.get("body").toString()).hasSize(DefaultPushSender.MAX_BODY_CHARS).endsWith("…");
    }

    @Test
    void aMalformedTokenIsRefused_withoutAskingTheService() throws Exception {
        DefaultPushSender sender = apns(a -> { });
        for (String token : List.of("a b", "abc%20", "../../x", "x?y=1", "")) {
            PushResult result = sender.send(new PushRequest(PushPlatform.IOS, token, "t", null, null, null, null,
                    Duration.ofHours(1)));
            assertThat(result.outcome()).isEqualTo(Outcome.REJECTED);
        }
        assertThat(seen).isEmpty();
    }

    @Test
    void whatAChannelThrowsComesBackAsARejection_andDoesNotPause() {
        PushChannel broken = request -> {
            throw new IllegalArgumentException("bad uri");
        };
        GuardedChannel channel = new GuardedChannel(PushPlatform.IOS, broken, 10, CLOCK);

        for (int i = 0; i < 20; i++) {
            assertThat(channel.send(request(PushPlatform.IOS, null)).outcome()).isEqualTo(Outcome.REJECTED);
        }
    }

    @Test
    void aRequestWithoutATimeToLiveKeepsTheDefault() throws Exception {
        apns(a -> { }).send(new PushRequest(PushPlatform.IOS, "token-1", "t", null, null, null, null, null));

        assertThat(seen.getFirst().headers().getFirst("apns-expiration")).isEqualTo(
                String.valueOf(CLOCK.instant().plus(DefaultPushSender.DEFAULT_TIME_TO_LIVE).getEpochSecond()));
    }

    @Test
    void aLongTitleReachesTheServiceCut() throws Exception {
        apns(a -> { }).send(new PushRequest(PushPlatform.IOS, "token-1", "标题".repeat(500), null, null, null, null,
                Duration.ofHours(1)));

        Map<?, ?> alert = (Map<?, ?>) ((Map<?, ?>) JSON.readValue(seen.getFirst().body(), Map.class).get("aps")).get("alert");
        assertThat(alert.get("title").toString().getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(DefaultPushSender.MAX_TITLE_BYTES);
    }

    /** A clock the test moves on by hand. */
    private static final class MutableClock extends Clock {
        private volatile Instant now = CLOCK.instant();

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    private static PushRequest request(PushPlatform platform, String body) {
        return new PushRequest(platform, "token-1", "title", body, Map.of("type", "APPROVAL", "targetId", "9"),
                "n-1", "APPROVAL", Duration.ofHours(24));
    }

    private static String pem(String type, byte[] encoded) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder().encodeToString(encoded)
                + "\n-----END " + type + "-----\n";
    }
}
