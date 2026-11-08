package io.softa.starter.message.config;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Centralised properties for {@code message-starter} infrastructure behaviour.
 * <p>
 * Bound under prefix {@code softa.message}. Each nested section corresponds
 * to one infrastructure concern. Defaults aim to be safe for production
 * single-node deployments; raise {@code mail.send-concurrency} /
 * {@code sms.send-concurrency} for throughput, raise {@code zombie.stale-seconds}
 * if a provider can legitimately take longer than five minutes to complete.
 *
 * <pre>{@code
 * softa:
 *   message:
 *     delivery:
 *       enabled: true              # default; set false on instances that must not send
 *       poll-interval-ms: 500      # how often idle workers look for newly due records
 *     zombie:
 *       enabled: true
 *       stale-seconds: 300         # stale SENDING records are revived
 *       cron: "0 * * * * *"        # sweep cadence (every minute)
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
    private final Zombie zombie = new Zombie();
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
     * The delivery loop ({@code DeliveryPoller}): claims due send records and runs them on the
     * channel worker pools sized by {@code mail.send-concurrency} / {@code sms.send-concurrency}.
     */
    @Data
    public static class Delivery {
        /** Whether this instance runs the delivery loop. */
        private boolean enabled = true;
        /**
         * How often, in milliseconds, idle workers look for newly due records. Busy workers do not
         * wait for it: each claims its next record as soon as it finishes a send. Lower = faster
         * pickup of new sends, more queries while idle.
         */
        private long pollIntervalMs = 500;
    }

    @Data
    public static class Zombie {
        /** Whether the {@code ZombieRecordSweeper} scheduled job is enabled. */
        private boolean enabled = true;
        /** Cron expression for the sweep cadence (default: every minute). */
        private String cron = "0 * * * * *";
        /** {@code SENDING} records older than this are revived. */
        private long staleSeconds = 300;
    }

    @Data
    public static class Mail {
        private final Fetch fetch = new Fetch();
        private final Transport transport = new Transport();
        private final SmtpPool smtpPool = new SmtpPool();

        /**
         * Maximum accepted length (in characters) for a single outbound mail
         * body field ({@code htmlBody} or {@code textBody}) at acceptance
         * time. A guard against runaway payloads (e.g. base64 images inlined
         * into the HTML), not a storage bound — the columns are unbounded
         * TEXT-class. Raise deliberately for deployments that legitimately
         * send larger bodies.
         */
        private int maxBodyChars = 1_048_576;

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

    @Data
    public static class Fetch {
        /**
         * Maximum number of messages to process per cron tick per (config, folder).
         * Server-side IMAP returns the full UID list cheaply; this caps how many
         * bodies we fetch and persist in one batch. The remainder is picked up by
         * the next cron tick.
         */
        private int batchLimit = 100;

        /**
         * Stale-lease threshold for {@code mail_fetch_imap_watermark.in_progress_since}.
         * If a worker holds the lease longer than this without releasing it, the
         * lease is considered abandoned (worker likely crashed) and another worker
         * may take over. Default is intentionally conservative — any legitimate
         * fetch should finish well within this window.
         */
        private Duration leaseTimeout = Duration.ofHours(1);

        /**
         * Total RFC822 size cap per email. Sourced from {@code message.getSize()}
         * (IMAP {@code RFC822.SIZE} / POP3 {@code LIST}), measured by the receiving
         * mail server — the sender cannot forge it. Above this limit the body is
         * never fetched; an envelope-only record is persisted with
         * {@code truncationReason=BodyTooLarge}.
         */
        private DataSize maxMessageSize = DataSize.ofMegabytes(100);

        /**
         * Per-attachment size cap. Sourced from MIME {@code BODYSTRUCTURE.size}
         * (no extra fetch). Attachments above this limit are skipped during upload
         * and do not appear in {@code MailReceiveRecord.attachments}; the first
         * occurrence sets {@code truncationReason=AttachmentTooLarge} so audit
         * can find emails whose attachments were truncated.
         */
        private DataSize maxAttachmentSize = DataSize.ofMegabytes(20);

        /**
         * Whether to archive the raw EML stream to {@link io.softa.framework.orm.service.FileService}.
         * Disabled by default — opt in for compliance / audit deployments. When
         * enabled, archive is bounded by {@code maxMessageSize} (oversized emails
         * are filtered before reaching this path).
         */
        private boolean archiveEml = false;

        /**
         * Maximum MIME nesting depth. Real-world emails rarely exceed 6 even with
         * S/MIME-signed-encrypted-alternative compositions; values beyond this are
         * treated as suspicious (potential MIME zip-bomb) and abort body extraction
         * with {@code truncationReason=MimeDepthExceeded}.
         */
        private int maxMimeDepth = 10;

        /**
         * Maximum total MIME parts walked per email. Defends against attachment-storm
         * attacks (a flat email with thousands of small parts that would each trigger
         * a {@link io.softa.framework.orm.service.FileService} upload). Normal emails
         * stay below 30; a hit aborts body extraction with
         * {@code truncationReason=MimePartsExceeded}.
         */
        private int maxMimeParts = 100;
    }

    /**
     * Reuse of authenticated SMTP connections across sends, one small pool per
     * {@code MailSendServerConfig}. Without it every mail pays a TCP connect, TLS
     * handshake and AUTH exchange, which is most of a small mail's send time.
     */
    @Data
    public static class SmtpPool {
        /** Reuse connections; {@code false} opens and closes one connection per send. */
        private boolean enabled = true;

        /**
         * An idle connection older than this is closed instead of reused. Keep it
         * well below the idle cut-off of any NAT or load balancer on the path to
         * the SMTP server (typically minutes): a connection one of those dropped
         * silently would only answer the reuse probe with a read timeout.
         */
        private Duration idleTimeout = Duration.ofSeconds(30);

        /**
         * Close a connection after it has carried this many messages. Some servers
         * cap messages per session; closing first avoids a failed send, and the
         * retry it would cost, when the cap is hit.
         */
        private int maxMessagesPerConnection = 100;
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
