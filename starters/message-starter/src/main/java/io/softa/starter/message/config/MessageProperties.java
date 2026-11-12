package io.softa.starter.message.config;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Centralised properties for {@code message-starter} infrastructure behaviour.
 * <p>
 * Bound under prefix {@code softa.message}. Only what differs between deployments is configurable:
 * how many sends an instance runs at once, the default monthly and daily quotas, transport timeouts,
 * the protocol debug log and the guard on tenants' private mail hosts. Retry back-off, the stale-send
 * window, polling, the SMTP pool's limits and mail-fetch limits are constants in the code that uses
 * them; connection reuse is switched per server config ({@code MailSendServerConfig.connectionReuseEnabled}).
 * Raise {@code mail.send-concurrency} / {@code sms.send-concurrency} for throughput, within what the mail
 * server or SMS provider allows.
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
 *       mail-daily-default: 2000    # per bucket per calendar day; unset = unlimited
 *       sms-daily-default: 2000
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
     * Deployment-default send ceilings, monthly and daily, used for any quota
     * bucket with no {@code TenantMessageQuota} row (or a null limit on its
     * row). Null =
     * unlimited. Per-bucket enforcement lives in {@code SendQuotaGuard}: a
     * send draws on the bucket of the tenant it is filed under, platform
     * notices made for a tenant included; sends no tenant can be named for draw
     * on the {@code tenantId = -1} bucket.
     */
    @Data
    public static class Quota {
        /** Default maximum mail sends charged per bucket per calendar month; null = unlimited. */
        private Long mailMonthlyDefault;
        /** Default maximum SMS sends charged per bucket per calendar month; null = unlimited. */
        private Long smsMonthlyDefault;
        /** Default maximum mail sends charged per bucket per calendar day; null = unlimited. */
        private Long mailDailyDefault;
        /** Default maximum SMS sends charged per bucket per calendar day; null = unlimited. */
        private Long smsDailyDefault;
    }

    @Data
    public static class Mail {
        private final Transport transport = new Transport();

        /**
         * Enable Jakarta Mail's full protocol debug log ({@code mail.debug=true})
         * on every session this app builds — SMTP sends and IMAP / POP3 fetches,
         * connectivity tests included.
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

        /**
         * Refuse a tenant's own mail server whose host is, or resolves to, an
         * address inside the deployment rather than on the internet: loopback,
         * link-local (the cloud metadata endpoint {@code 169.254.169.254}
         * included), private ranges, carrier-grade NAT, the unspecified
         * address and multicast, IPv4 and IPv6. Checked when the server is
         * saved and again before every connection, so a name that later
         * resolves inward is caught too. Otherwise a tenant could point the
         * application at internal services and read their banners and errors
         * back through the connection test.
         * <p>
         * Applies only with multi-tenancy enabled, and only to tenants'
         * servers — the platform's own are trusted configuration. Turn it off
         * where tenants' servers legitimately sit on a private network, such
         * as a test environment whose tenants send through a local mail
         * catcher.
         */
        private boolean blockPrivateTenantHosts = true;
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
     * Generic outbound transport timeouts, one set per channel. Applied to:
     * <ul>
     *   <li>every Jakarta Mail {@link jakarta.mail.Session} — SMTP sends, IMAP / POP3
     *       fetches and connectivity tests — as {@code mail.{protocol}.connectiontimeout}
     *       and {@code mail.{protocol}.timeout} ({@code MailSessionSettings});</li>
     *   <li>the SMS providers' HTTPS {@code RestClient} — connection and read
     *       timeouts on the HTTP client.</li>
     * </ul>
     * Connection plus read timeout may be at most half the 5-minute window after
     * which a record still {@code SENDING} is taken for a crashed send, and
     * neither may be zero (no timeout): startup refuses both
     * ({@code ZombieRecordSweeper}), since a send that is only slow would be
     * revived while in flight and sent twice.
     * <p>
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
