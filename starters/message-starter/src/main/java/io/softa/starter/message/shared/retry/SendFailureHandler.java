package io.softa.starter.message.shared.retry;

import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.softa.framework.base.context.ContextHolder;
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
 * will not go out — a missing server, provider or template included. Each
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
        log.error("{} send moved to dead-letter [{}] for record id={} tenantId={} after {} attempts: [{}] {}",
                channel, category, recordId, ContextHolder.getContext().getTenantId(), attempts,
                errorCode, errorMessage);
        return true;
    }

    /** A superseded version means another worker already progressed the record — a safe no-op. */
    private static void logCasMiss(String channel, Long recordId, String disposition) {
        log.debug("{} {} transition skipped for record id={} — version superseded (concurrent update won)",
                channel, disposition, recordId);
    }
}
