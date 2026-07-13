package io.softa.starter.message.mail.service;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Map;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.message.mail.entity.MailSendRecord;
import io.softa.starter.message.mail.enums.MailSendStatus;

/**
 * CRUD service for outgoing mail records.
 */
public interface MailSendRecordService extends EntityService<MailSendRecord, Long> {

    /**
     * Resolve many SMTP Message-IDs with a single {@code IN (...)} query. Returns a
     * map keyed by Message-ID; missing ids simply aren't in the map, and order is
     * not preserved. Used when correlating a batch of inbound mails (bounces /
     * receipts) back to their original sent messages, avoiding N+1 queries.
     */
    Map<String, MailSendRecord> findByMessageIds(Collection<String> messageIds);

    /**
     * Compare-and-set the record's status + version. Returns true iff the row
     * was updated (current {@code version} matched {@code expectedVersion}).
     * <p>
     * Used to claim a record for a send attempt: the caller transitions
     * PENDING/RETRY → SENDING; a losing concurrent attempt gets {@code false}
     * and must treat its delivery as a duplicate.
     */
    boolean casStatus(Long id, long expectedVersion, MailSendStatus next);

    /**
     * Mark a record as terminally SENT with the SMTP Message-ID and transport name.
     * Bumps version; no-op if version mismatches.
     */
    void markSent(Long id, long expectedVersion, String messageId, String providerName);

    /**
     * Mark a record for retry (status=RETRY, retryCount++, nextRetryAt set,
     * error code / message persisted). Bumps version.
     */
    boolean markRetry(Long id, long expectedVersion, String errorCode, String errorMessage,
                      LocalDateTime nextRetryAt);

    /**
     * Terminally fail a record with no more retries. Bumps version.
     */
    boolean markFailed(Long id, long expectedVersion, String errorCode, String errorMessage);

    /**
     * Move a record to DEAD_LETTER after exhausting retries. Bumps version.
     */
    boolean markDeadLetter(Long id, long expectedVersion, String errorCode, String errorMessage);

    /**
     * Record an inbound bounce against a sent message. Flips {@code bounced=true},
     * stores {@code bounceCode}, transitions the record to {@code FAILED}, and
     * bumps {@code version}. Returns {@code false} if the version didn't match —
     * callers treat a CAS miss as "another update won, skip".
     */
    boolean markBounced(Long id, long expectedVersion, String bounceCode);

    /**
     * Record that a read receipt arrived for this send. Sets
     * {@code read_receipt_received=true}, {@code read_receipt_received_at=now},
     * bumps {@code version}. Does not change {@code status}.
     */
    boolean markReadReceiptReceived(Long id, long expectedVersion);
}
