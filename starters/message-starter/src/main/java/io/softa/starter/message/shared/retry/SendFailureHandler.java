package io.softa.starter.message.shared.retry;

import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.softa.starter.message.dlq.service.DeadLetterMessageService;
import io.softa.starter.message.mq.TopicRoute;
import io.softa.starter.message.mq.outbox.OutboxRecordWriter;
import io.softa.starter.message.shared.ErrorCategory;
import io.softa.starter.message.shared.ErrorClassifier;
import io.softa.starter.message.shared.metrics.MessageMetrics;

/**
 * Shared failure handling for the mail and sms send paths: classify the
 * provider error, ask {@link ExponentialBackoffPolicy} for a decision, and
 * apply it under the channel's CAS helpers — retry (CAS + delayed outbox row,
 * made atomic by {@link OutboxRecordWriter}), terminal fail, or dead-letter.
 * <p>
 * The two channels were byte-for-byte identical here; extracting it keeps the
 * subtle atomic-CAS + delayed-enqueue contract in one place so they can't
 * diverge. Each channel supplies its CAS helpers via {@link RecordTransitions}.
 */
@Slf4j
@Component
public class SendFailureHandler {

    @Autowired
    private ErrorClassifier errorClassifier;

    @Autowired
    private ExponentialBackoffPolicy retryPolicy;

    @Autowired
    private OutboxRecordWriter outboxRecordWriter;

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
     * @param deliveryRoute   channel delivery route used for both initial sends and delayed retries
     * @param recordType      outbox aggregate type (record entity name)
     * @param recordId        the send-record id
     * @param expectedVersion CAS version the caller claimed
     * @param currentAttempts retries already made
     * @param errorCode       provider error code
     * @param errorMessage    provider error message
     * @param tx              channel CAS helpers
     */
    public void handle(String channel, String provider, TopicRoute deliveryRoute, String recordType,
                       Long recordId, long expectedVersion, int currentAttempts,
                       String errorCode, String errorMessage, RecordTransitions tx) {
        ErrorCategory category = errorClassifier.classify(errorCode, errorMessage);
        RetryDecision decision = retryPolicy.decide(currentAttempts, category);

        switch (decision) {
            case RetryDecision.Retry r -> {
                LocalDateTime next = LocalDateTime.now().plusSeconds(r.delay().getSeconds());
                // CAS to RETRY + enqueue the delayed retry row atomically, so a
                // crash between them can't strand a RETRY record with no outbox row.
                boolean applied = outboxRecordWriter.transitionAndEnqueueAt(
                        () -> tx.markRetry().apply(recordId, expectedVersion, errorCode, errorMessage, next),
                        recordId, recordType, deliveryRoute, next);
                if (!applied) {
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
                log.error("{} send failed terminally [{}] for record id={}: [{}] {}",
                        channel, category, recordId, errorCode, errorMessage);
            }
            case RetryDecision.DeadLetter d -> {
                if (!tx.markDeadLetter().apply(recordId, expectedVersion, errorCode, errorMessage)) {
                    logCasMiss(channel, recordId, "dead_letter");
                    return;
                }
                // Archive into the unified dead-letter store for triage (source = SEND_EXHAUSTED).
                deadLetterMessageService.archiveSendExhausted(
                        channel, recordId, provider, errorCode, errorMessage, category, currentAttempts);
                if (metrics != null) metrics.failed(channel, provider, "dead_letter");
                log.error("{} send moved to dead-letter [{}] for record id={} after {} attempts: [{}] {}",
                        channel, category, recordId, currentAttempts, errorCode, errorMessage);
            }
        }
    }

    /** A superseded version means another worker already progressed the record — a safe no-op. */
    private static void logCasMiss(String channel, Long recordId, String disposition) {
        log.debug("{} {} transition skipped for record id={} — version superseded (concurrent update won)",
                channel, disposition, recordId);
    }
}
