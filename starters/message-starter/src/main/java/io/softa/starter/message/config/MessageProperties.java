package io.softa.starter.message.config;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Centralised properties for {@code message-starter} infrastructure behaviour.
 * <p>
 * Bound under prefix {@code softa.message}. Only what differs between deployments is configurable:
 * how many sends an instance runs at once, the default monthly quotas, transport timeouts and the
 * protocol debug log. Retry back-off, the stale-send window, polling, the SMTP pool's limits and
 * mail-fetch limits are constants in the code that uses them; connection reuse is switched per server
 * config ({@code MailSendServerConfig.connectionReuseEnabled}). Raise {@code mail.send-concurrency} /
 * {@code sms.send-concurrency} for throughput, within what the mail server or SMS provider allows.
 *
 * <pre>{@code
 * softa:
 *   message:
 *     mail:
 *       send-concurrency: 4        # mails one instance sends at once (each worker holds an SMTP connection)
 *     sms:
 *       send-concurrency: 10       # SMS one instance sends at once
 *     quota:
 *       mail-monthly-default: 10000
 *       sms-monthly-default: 10000
 * }</pre>
 *
 * <h3>Why some keys still appear as {@code @Value} / {@code @ConditionalOnProperty} literals</h3>
 * Spring evaluates {@code @ConditionalOnProperty} and {@code @Scheduled} at
 * bean-creation time, before fully bound configuration objects are available
 * for injection. Those annotations therefore continue to reference the raw
 * property keys directly. This class still provides IDE auto-completion,
 * type safety for programmatic reads, and a single documented place for the
 * default values.
 */
@Data
@ConfigurationProperties(prefix = "softa.message")
public class MessageProperties {

    private final Mail mail = new Mail();
    private final Sms sms = new Sms();
    private final Quota quota = new Quota();

    /**
     * Deployment-default monthly send ceilings, used for any quota bucket with
     * no {@code TenantMessageQuota} row (or a null limit on its row). Null =
     * unlimited. Per-bucket enforcement lives in {@code MonthlyQuotaGuard};
     * the platform's own sends draw on the {@code tenantId = -1} bucket.
     */
    @Data
    public static class Quota {
        /** Default maximum mail sends charged per bucket per calendar month; null = unlimited. */
        private Long mailMonthlyDefault;
        /** Default maximum SMS sends charged per bucket per calendar month; null = unlimited. */
        private Long smsMonthlyDefault;
    }

    @Data
    public static class Mail {
        private final Transport transport = new Transport();

        /**
         * Enable Jakarta Mail's full SMTP/IMAP protocol debug log
         * ({@code mail.debug=true}) on every transport built by this app.
         * <p>
         * <b>Use only in non-production environments.</b> Jakarta Mail's debug
         * output writes the AUTH exchange (Base64-encoded but trivially decoded)
         * straight to stdout — turning this on in prod leaks SMTP/IMAP
         * credentials into application logs. For deployed-environment
         * troubleshooting prefer a redacted JavaMail
         * {@code ProtocolEventListener}.
         * <p>
         * Operator-controlled (Spring config), not data-controlled — flip it
         * via env var or {@code application-*.yml} when reproducing an issue
         * locally, then disable.
         */
        private boolean debug = false;

        /**
         * How many mails one instance sends in parallel: the size of its mail
         * worker pool in the delivery loop. Each worker holds its own SMTP
         * connection, so stay within the server's limit on concurrent connections
         * per account; lower it for a server that allows fewer than four.
         */
        private int sendConcurrency = 4;
    }

    @Data
    public static class Sms {
        private final Transport transport = new Transport();

        /**
         * How many SMS one instance sends in parallel: the size of its SMS worker pool. Each send is
         * one HTTPS call, so ten stays well below the request rates SMS providers allow.
         */
        private int sendConcurrency = 10;
    }

    /**
     * Generic outbound transport timeouts. Applied by:
     * <ul>
     *   <li>{@code SmtpMailTransport} when building the SMTP/IMAP/POP3
     *       {@link jakarta.mail.Session} — {@code mail.{protocol}.connectiontimeout}
     *       and {@code mail.{protocol}.timeout}.</li>
     *   <li>SMS provider adapters when building their HTTPS {@code RestClient}
     *       — connection and read timeouts on the HTTP client.</li>
     * </ul>
     * Single set of defaults across all configs intentionally: per-config
     * tunability is YAGNI for the framework's typical providers (Gmail /
     * Outlook / Twilio / Aliyun all respond sub-second). If a future provider
     * needs different timeouts, reintroduce per-config fields then.
     */
    @Data
    public static class Transport {
        /**
         * Socket connection timeout. Generous default; tune down for fast
         * providers to fail faster, up for high-latency cross-region links.
         */
        private Duration connectionTimeout = Duration.ofSeconds(5);

        /**
         * Per-operation read timeout. Mail: SMTP send / IMAP fetch round trip.
         * SMS: HTTP response. Default absorbs typical provider-side rate-limit
         * waits without timing out a legitimate slow response.
         */
        private Duration readTimeout = Duration.ofSeconds(30);
    }
}
