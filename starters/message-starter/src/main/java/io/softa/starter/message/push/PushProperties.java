package io.softa.starter.message.push;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Push notification settings, under {@code softa.message.push}. Credentials belong in environment variables, never in
 * a repository. A platform whose credentials are blank is simply off.
 */
@Data
@ConfigurationProperties(prefix = "softa.message.push")
public class PushProperties {

    /** Log each notification instead of sending it, on both platforms: for trying the flow without any account. */
    private boolean logOnly = false;

    /** The most requests in flight per platform, per instance. */
    private int concurrency = 100;

    private Duration connectTimeout = Duration.ofSeconds(5);

    private Duration requestTimeout = Duration.ofSeconds(10);

    private final Apns apns = new Apns();

    private final Fcm fcm = new Fcm();

    @Data
    public static class Apns {
        /** The {@code .p8} private key: its PEM text, or that text base64-encoded. */
        private String privateKey;
        private String keyId;
        private String teamId;
        /** The app's bundle id. */
        private String topic;
        /** {@code production} (TestFlight and store builds) or {@code sandbox} (builds installed from Xcode). */
        private String environment = "production";
        /**
         * Try {@code sandbox} when {@code production} calls a token bad, for an environment that has both kinds of
         * build. Off where only one kind connects, so a token that is really bad is not sent twice.
         */
        private boolean sandboxFallback = false;
        private String baseUrl = "https://api.push.apple.com";
        private String sandboxBaseUrl = "https://api.sandbox.push.apple.com";
        private String proxyHost;
        private Integer proxyPort;
    }

    @Data
    public static class Fcm {
        /** The service account key: its JSON text, or that text base64-encoded. */
        private String serviceAccountJson;
        /** The Android notification channel to show on; blank leaves the choice to the app's default channel. */
        private String androidChannelId;
        private String baseUrl = "https://fcm.googleapis.com";
        /** Where to exchange the key for an access token; blank takes the {@code token_uri} in the key. */
        private String tokenUrl;
        private String proxyHost;
        private Integer proxyPort;
    }
}
