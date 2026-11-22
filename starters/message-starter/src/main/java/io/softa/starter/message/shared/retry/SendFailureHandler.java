package io.softa.starter.message.shared.retry;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.context.MdcKeys;
import io.softa.starter.message.dlq.service.DeadLetterMessageService;
import io.softa.starter.message.shared.ErrorCategory;
import io.softa.starter.message.shared.ErrorClassifier;
import io.softa.starter.message.shared.metrics.MessageMetrics;

/**
 * Shared failure handling for the mail and sms send paths: classify the
 * error, ask {@link ExponentialBackoffPolicy} for a decision, and apply it
 * under the channel's CAS helpers — retry (RETRY with {@code nextRetryAt}, which
 * the delivery loop polls for), terminal fail, or dead-letter.
 * <p>
 * Every terminal outcome is logged at ERROR with the record id, the error code
 * and the reason, so log-based alerting reaches a developer for each send that
 * will not go out — a missing server, provider or template included. While it
 * logs, the channel, provider, category and code are {@code tag.*} MDC entries
 * ({@link FailureTags}), which sentry-starter turns into tags on the event. Each
 * channel supplies its CAS helpers via {@link RecordTransitions}.
 */
@Slf4j
@Component
public class SendFailureHandler {

    @Autowired
    private ErrorClassifier errorClassifier;

    @Autowired
    private ExponentialBackoffPolicy retryPolicy;

    @Autowired
    private DeadLetterMessageService deadLetterMessageService;

    @Autowired(required = false)
    private MessageMetrics metrics;

    /**
     * The channel-specific CAS state transitions. Their signatures are already
     * identical across {@code MailSendRecordService} / {@code SmsSendRecordService},
     * so callers pass them as method references.
     */
    public record RecordTransitions(MarkRetry markRetry, MarkTerminal markFailed, MarkTerminal markDeadLetter) {
        @FunctionalInterface
        public interface MarkRetry {
            boolean apply(Long id, long expectedVersion, String errorCode, String errorMessage, LocalDateTime next);
        }

        /**
         * Terminal transition. Returns the CAS outcome — {@code false} means the
         * version was superseded and the transition did NOT happen; the handler
         * must not emit dead-letter rows, metrics, or terminal logs for it.
         */
        @FunctionalInterface
        public interface MarkTerminal {
            boolean apply(Long id, long expectedVersion, String errorCode, String errorMessage);
        }
    }

    /**
     * Classify the failure and apply the resulting disposition.
     *
     * @param channel         {@code "mail"} / {@code "sms"} — metrics + log tag
     * @param provider        transport / provider name (nullable), for metrics + dead-letter
     * @param recordId        the send-record id
     * @param expectedVersion CAS version the caller claimed
     * @param currentAttempts retries already made
     * @param errorCode       provider error code
     * @param errorMessage    provider error message
     * @param tx              channel CAS helpers
     */
    public void handle(String channel, String provider,
                       Long recordId, long expectedVersion, int currentAttempts,
                       String errorCode, String errorMessage, RecordTransitions tx) {
        ErrorCategory category = errorClassifier.classify(errorCode, errorMessage);
        RetryDecision decision = retryPolicy.decide(currentAttempts, category);

        try (FailureTags ignored = FailureTags.put(channel, provider, category, errorCode)) {
            apply(decision, channel, provider, recordId, expectedVersion, currentAttempts,
                    errorCode, errorMessage, category, tx);
        }
    }

    private void apply(RetryDecision decision, String channel, String provider,
                       Long recordId, long expectedVersion, int currentAttempts,
                       String errorCode, String errorMessage, ErrorCategory category, RecordTransitions tx) {
        switch (decision) {
            case RetryDecision.Retry r -> {
                LocalDateTime next = LocalDateTime.now().plusSeconds(r.delay().getSeconds());
                if (!tx.markRetry().apply(recordId, expectedVersion, errorCode, errorMessage, next)) {
                    logCasMiss(channel, recordId, "retry");
                    return;
                }
                if (metrics != null) metrics.failed(channel, provider, "retry");
                log.warn("{} send failed [{}] — retry in {}s for record id={}: [{}] {}",
                        channel, category, r.delay().getSeconds(), recordId, errorCode, errorMessage);
            }
            case RetryDecision.Fail f -> {
                if (!tx.markFailed().apply(recordId, expectedVersion, errorCode, errorMessage)) {
                    logCasMiss(channel, recordId, "failed");
                    return;
                }
                if (metrics != null) metrics.failed(channel, provider, "failed");
                log.error("{} send failed terminally [{}] for record id={} tenantId={}: [{}] {}",
                        channel, category, recordId, ContextHolder.getContext().getTenantId(),
                        errorCode, errorMessage);
            }
            case RetryDecision.DeadLetter d -> deadLetter(channel, provider, recordId, expectedVersion,
                    currentAttempts, errorCode, errorMessage, category, tx.markDeadLetter());
        }
    }

    /**
     * Move a record to DEAD_LETTER and, only if that CAS wins, archive it into the
     * unified dead-letter store (source = SEND_EXHAUSTED) and count it. Shared by
     * the retry decision above and by the zombie sweeper, which dead-letters a
     * record that keeps getting stuck once its retry budget is spent.
     *
     * @return the CAS outcome; {@code false} means another update won and nothing was archived
     */
    public boolean deadLetter(String channel, String provider, Long recordId, long expectedVersion,
                              int attempts, String errorCode, String errorMessage, ErrorCategory category,
                              RecordTransitions.MarkTerminal markDeadLetter) {
        if (!markDeadLetter.apply(recordId, expectedVersion, errorCode, errorMessage)) {
            logCasMiss(channel, recordId, "dead_letter");
            return false;
        }
        deadLetterMessageService.archiveSendExhausted(
                channel, recordId, provider, errorCode, errorMessage, category, attempts);
        if (metrics != null) metrics.failed(channel, provider, "dead_letter");
        // The zombie sweep calls this directly, so the tags are put here as well as in handle().
        try (FailureTags ignored = FailureTags.put(channel, provider, category, errorCode)) {
            log.error("{} send moved to dead-letter [{}] for record id={} tenantId={} after {} attempts: [{}] {}",
                    channel, category, recordId, ContextHolder.getContext().getTenantId(), attempts,
                    errorCode, errorMessage);
        }
        return true;
    }

    /** A superseded version means another worker already progressed the record — a safe no-op. */
    private static void logCasMiss(String channel, Long recordId, String disposition) {
        log.debug("{} {} transition skipped for record id={} — version superseded (concurrent update won)",
                channel, disposition, recordId);
    }

    /**
     * The failure's labels as {@link MdcKeys#TAG_PREFIX tag.*} MDC entries for the length of its
     * log calls, so the event sentry-starter makes of an ERROR line can be filtered by channel,
     * provider, category and code. Closing restores whatever the entries held before, so a nested
     * put (handle → deadLetter) leaves the outer one intact.
     */
    static final class FailureTags implements AutoCloseable {

        static final String CHANNEL = MdcKeys.TAG_PREFIX + "message_channel";
        static final String PROVIDER = MdcKeys.TAG_PREFIX + "message_provider";
        static final String CATEGORY = MdcKeys.TAG_PREFIX + "error_category";
        static final String CODE = MdcKeys.TAG_PREFIX + "error_code";

        private final Map<String, String> previous = new LinkedHashMap<>();

        static FailureTags put(String channel, String provider, ErrorCategory category, String errorCode) {
            FailureTags tags = new FailureTags();
            tags.set(CHANNEL, channel);
            tags.set(PROVIDER, provider);
            tags.set(CATEGORY, category != null ? category.name() : null);
            tags.set(CODE, errorCode);
            return tags;
        }

        private void set(String key, String value) {
            previous.put(key, MDC.get(key));
            if (value == null || value.isBlank()) {
                MDC.remove(key);
            } else {
                MDC.put(key, value);
            }
        }

        @Override
        public void close() {
            previous.forEach((key, value) -> {
                if (value == null) {
                    MDC.remove(key);
                } else {
                    MDC.put(key, value);
                }
            });
        }
    }
}
