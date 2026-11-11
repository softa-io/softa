package io.softa.starter.message.mail.service;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.message.mail.entity.MailSendRecord;
import io.softa.starter.message.mail.enums.MailServerOwner;

/**
 * CRUD service for outgoing mail records.
 */
public interface MailSendRecordService extends EntityService<MailSendRecord, Long> {

    /**
     * Resolve many SMTP Message-IDs with a single {@code IN (...)} query, among the records whose mail
     * a server of {@code carriedBy} sent — the platform's (every tenant's relayed mail included), or the
     * current tenant's own. Returns a map keyed by Message-ID; missing ids simply aren't in the map.
     * Used when correlating a batch of inbound mails (bounces / receipts) back to their original sent
     * messages: a receive server only ever answers for the mail its own owner's send servers carried.
     */
    Map<String, MailSendRecord> findByMessageIds(Collection<String> messageIds, MailServerOwner carriedBy);

    /**
     * The record, when the current caller may see it: the platform sees every record, a tenant only
     * those filed under it. The table is not multi-tenant, so a by-id read reaching a tenant goes
     * through here.
     */
    Optional<MailSendRecord> findVisibleById(Long id);

    /** {@link #retry} for a record the current caller may see ({@link #findVisibleById}). */
    boolean retryVisible(Long id);

    /** {@link #retryByIds} over the records the current caller may see; the others are skipped. */
    int retryVisibleByIds(List<Long> ids);

    /**
     * Claim a record for a send attempt: compare-and-set PENDING/RETRY → SENDING
     * and stamp {@code attemptStartedAt}. Returns true iff the row was updated
     * (current {@code version} matched {@code expectedVersion}); a losing
     * concurrent claim gets {@code false} and must leave the record alone.
     */
    boolean markSending(Long id, long expectedVersion);

    /**
     * Write back what the first delivery attempt prepared — rendered subject and
     * bodies, template defaults, the chosen server and sender — so retries replay
     * it. Bumps version; {@code false} if the version did not match.
     */
    boolean markPrepared(MailSendRecord record, long expectedVersion);

    /**
     * Mark a record as terminally SENT with the SMTP Message-ID, transport name,
     * and how long the SMTP call took. Bumps version; no-op if version mismatches.
     */
    void markSent(Long id, long expectedVersion, String messageId, String providerName, int sendDurationMs);

    /**
     * Mark a record for retry (status=RETRY, retryCount++, nextRetryAt set,
     * error code / message persisted). Bumps version.
     */
    boolean markRetry(Long id, long expectedVersion, String errorCode, String errorMessage,
                      LocalDateTime nextRetryAt);

    /**
     * Manually requeue one record for delivery: {@code PENDING} / {@code RETRY} /
     * {@code FAILED} / {@code DEAD_LETTER} → {@code RETRY}, due now, so the delivery
     * loop takes it on its next poll. A record that failed before it was prepared
     * (no server, no template, quota used up) is prepared afresh, so fixing the
     * configuration and retrying is enough. {@code SENT} and in-flight
     * {@code SENDING} records are rejected with a business error — stuck SENDING
     * is the zombie sweeper's job.
     */
    boolean retry(Long id);

    /**
     * Requeue the selected records the way {@link #retry} does, skipping what it
     * would reject instead of failing: {@code SENT} and in-flight {@code SENDING}
     * records, a record that changed while being requeued (a worker claimed it),
     * and ids that are not found. Returns how many were requeued.
     */
    int retryByIds(List<Long> ids);

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
