package io.softa.starter.message.config;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Centralised properties for {@code message-starter} infrastructure behaviour
 * that isn't part of the MQ topic map (which lives in
 * {@link io.softa.starter.message.mq.MqTopicsProperties}).
 * <p>
 * Bound under prefix {@code softa.message}. Each nested section corresponds
 * to one infrastructure concern. Defaults aim to be safe for production
 * single-node deployments; tune {@code outbox.poll-interval-ms} down to
     * raise throughput, raise {@code zombie.stale-seconds} if the broker or
     * provider can legitimately take longer than five minutes to complete.
 *
 * <pre>{@code
 * softa:
 *   message:
 *     outbox:
 *       enabled: true              # default; set false on read-only replicas
 *       poll-interval-ms: 500      # publisher polling cadence
 *     zombie:
 *       enabled: true
     *       stale-seconds: 300         # stale SENDING / PUBLISHING claims are revived
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

    private final Outbox outbox = new Outbox();
    private final Zombie zombie = new Zombie();
    private final Mail mail = new Mail();
    private final Sms sms = new Sms();

    @Data
    public static class Outbox {
        /** Whether the {@code OutboxPublisher} scheduled job is enabled. */
        private boolean enabled = true;
        /** Polling interval in milliseconds. Lower = faster publish, higher DB load. */
        private long pollIntervalMs = 500;
    }

    @Data
    public static class Zombie {
        /** Whether the {@code ZombieRecordSweeper} scheduled job is enabled. */
        private boolean enabled = true;
        /** Cron expression for the sweep cadence (default: every minute). */
        private String cron = "0 * * * * *";
        /** Stale {@code SENDING} records and {@code PUBLISHING} outbox claims are revived. */
        private long staleSeconds = 300;
    }

    @Data
    public static class Mail {
        private final Fetch fetch = new Fetch();
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

    @Data
    public static class Sms {
        private final Transport transport = new Transport();
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
