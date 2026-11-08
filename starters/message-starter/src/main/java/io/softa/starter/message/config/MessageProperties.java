package io.softa.starter.message.config;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Centralised properties for {@code message-starter} infrastructure behaviour.
 * <p>
 * Bound under prefix {@code softa.message}. Only what differs between deployments is configurable:
 * whether an instance sends, how many sends it runs at once, the default monthly quotas, transport
 * timeouts, and a few switches. Retry back-off, the stale-send window, polling and mail-fetch limits
 * are constants in the code that uses them. Raise {@code mail.send-concurrency} /
 * {@code sms.send-concurrency} for throughput, within what the mail server or SMS provider allows.
 *
 * <pre>{@code
 * softa:
 *   message:
 *     delivery:
 *       enabled: true              # default; set false on instances that must not send
 *     mail:
 *       send-concurrency: 1        # mails one instance sends at once (each worker holds an SMTP connection)
 *     sms:
 *       send-concurrency: 1        # SMS one instance sends at once
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

    private final Delivery delivery = new Delivery();
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

    /**
     * The delivery loop ({@code DeliveryPoller}) and the {@code ZombieRecordSweeper} that revives its
     * stalled sends; the worker pools are sized by {@code mail.send-concurrency} /
     * {@code sms.send-concurrency}.
     */
    @Data
    public static class Delivery {
        /** Whether this instance sends: runs the delivery loop and the stale-send sweep. */
        private boolean enabled = true;
    }

    @Data
    public static class Mail {
        private final Fetch fetch = new Fetch();
        private final Transport transport = new Transport();
        private final SmtpPool smtpPool = new SmtpPool();

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
         * per account.
         */
        private int sendConcurrency = 1;
    }

    /** Scheduled mail fetch. Its batch, lease and size limits are constants in the fetch code. */
    @Data
    public static class Fetch {
        /**
         * Whether to archive the raw EML stream to {@link io.softa.framework.orm.service.FileService}.
         * Disabled by default — opt in for compliance / audit deployments. Oversized emails are
         * filtered before reaching this path.
         */
        private boolean archiveEml = false;
    }

    /**
     * Reuse of authenticated SMTP connections across sends, one small pool per
     * {@code MailSendServerConfig}. Without it every mail pays a TCP connect, TLS
     * handshake and AUTH exchange, which is most of a small mail's send time.
     * The idle cut-off and messages per connection are constants in {@code PooledSmtpSender}.
     */
    @Data
    public static class SmtpPool {
        /**
         * Reuse connections; {@code false} opens and closes one connection per send — the way out
         * for a server that does not take connection reuse well.
         */
        private boolean enabled = true;
    }

    @Data
    public static class Sms {
        private final Transport transport = new Transport();

        /** How many SMS one instance sends in parallel: the size of its SMS worker pool. */
        private int sendConcurrency = 1;
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
