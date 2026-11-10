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
        return new DefaultPushSender(properties, CLOCK);
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
        assertApns(sender, 410, "{\"reason\":\"Unregistered\"}", Outcome.TOKEN_INVALID, "Unregistered");
        assertApns(sender, 400, "{\"reason\":\"BadDeviceToken\"}", Outcome.REJECTED, "BadDeviceToken");
        assertApns(sender, 400, "{\"reason\":\"DeviceTokenNotForTopic\"}", Outcome.CONFIG_ERROR, "DeviceTokenNotForTopic");
        assertApns(sender, 403, "{\"reason\":\"InvalidProviderToken\"}", Outcome.CONFIG_ERROR, "InvalidProviderToken");
        assertApns(sender, 400, "{\"reason\":\"PayloadEmpty\"}", Outcome.REJECTED, "PayloadEmpty");
        assertApns(sender, 500, "{\"reason\":\"InternalServerError\"}", Outcome.RETRY, "InternalServerError");
        assertApns(sender, 503, "", Outcome.RETRY, "HTTP 503");
    }

    private void assertApns(DefaultPushSender sender, int status, String body, Outcome outcome, String reason) {
        answers = request -> Answer.of(status, body);
        PushResult result = sender.send(request(PushPlatform.IOS, null));
        assertThat(result.outcome()).isEqualTo(outcome);
        assertThat(result.reason()).isEqualTo(reason);
    }

    @Test
    void anExpiredProviderTokenIsResigned() throws Exception {
        DefaultPushSender sender = apns(a -> { });
        answers = request -> Answer.of(403, "{\"reason\":\"ExpiredProviderToken\"}");

        assertThat(sender.send(request(PushPlatform.IOS, null)).outcome()).isEqualTo(Outcome.RETRY);
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
    void retryAfterPausesThePlatform() throws Exception {
        DefaultPushSender sender = apns(a -> { });
        answers = request -> new Answer(429, "{\"reason\":\"TooManyRequests\"}", Map.of("Retry-After", "120"));

        PushResult limited = sender.send(request(PushPlatform.IOS, null));
        assertThat(limited.outcome()).isEqualTo(Outcome.RETRY);
        assertThat(limited.retryAfter()).isEqualTo(Duration.ofSeconds(120));

        // Paused: the second send never reaches the service.
        PushResult paused = sender.send(request(PushPlatform.IOS, null));
        assertThat(paused.outcome()).isEqualTo(Outcome.RETRY);
        assertThat(seen).hasSize(1);
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
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair key = generator.generateKeyPair();
        String account = JSON.writeValueAsString(Map.of("project_id", "demo", "client_email", "push@demo.iam",
                "private_key_id", "kid-1", "private_key", pem("PRIVATE KEY", key.getPrivate().getEncoded()),
                "token_uri", baseUrl + "/token"));
        PushProperties properties = new PushProperties();
        // Base64, the form it takes in an environment file.
        properties.getFcm().setServiceAccountJson(Base64.getEncoder().encodeToString(account.getBytes(StandardCharsets.UTF_8)));
        properties.getFcm().setBaseUrl(baseUrl);
        properties.getFcm().setAndroidChannelId("approvals");
        return new DefaultPushSender(properties, CLOCK);
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
        assertFcm(sender, 404, fcmError("NOT_FOUND", "UNREGISTERED"), Outcome.TOKEN_INVALID, "UNREGISTERED");
        assertFcm(sender, 404, "<html>not found</html>", Outcome.REJECTED, "HTTP 404");
        assertFcm(sender, 403, fcmError("PERMISSION_DENIED", "SENDER_ID_MISMATCH"), Outcome.CONFIG_ERROR, "SENDER_ID_MISMATCH");
        assertFcm(sender, 400, fcmError("INVALID_ARGUMENT", "INVALID_ARGUMENT"), Outcome.REJECTED, "INVALID_ARGUMENT");
        assertFcm(sender, 500, fcmError("INTERNAL", "INTERNAL"), Outcome.RETRY, "INTERNAL");
        assertFcm(sender, 503, "<html>gateway</html>", Outcome.RETRY, "HTTP 503");
        assertFcm(sender, 401, fcmError("UNAUTHENTICATED", "THIRD_PARTY_AUTH_ERROR"), Outcome.CONFIG_ERROR, "THIRD_PARTY_AUTH_ERROR");
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
    void aTokenEndpointThatIsDownIsWorthRetrying() throws Exception {
        answers = request -> Answer.of(503, "");

        assertThat(fcm().send(request(PushPlatform.ANDROID, null)).outcome()).isEqualTo(Outcome.RETRY);
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
    void anUnreachableServiceIsARetry_andPausesAfterTenFailures() throws Exception {
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

        assertThat(results).allMatch(result -> result.outcome() == Outcome.RETRY);
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
    void aLongBodyIsCutOnACharacterBoundary() {
        String cut = DefaultPushSender.fit("意见".repeat(1000), 1000);

        assertThat(cut.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(1000);
        assertThat(cut).endsWith("…").doesNotContain("�");
        assertThat(DefaultPushSender.fit("short", 1000)).isEqualTo("short");
        assertThat(DefaultPushSender.fit(null, 1000)).isNull();
    }

    @Test
    void aLongBodyReachesTheServiceCut() throws Exception {
        apns(a -> { }).send(request(PushPlatform.IOS, "x".repeat(5000)));

        assertThat(seen.getFirst().body().getBytes(StandardCharsets.UTF_8).length).isLessThan(4096);
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
