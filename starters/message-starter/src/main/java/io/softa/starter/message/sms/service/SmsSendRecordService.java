package io.softa.starter.message.sms.service;

import java.time.LocalDateTime;
import java.util.List;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.message.sms.entity.SmsSendRecord;
import io.softa.starter.message.sms.enums.SmsProvider;

/**
 * CRUD service for SMS send records.
 */
public interface SmsSendRecordService extends EntityService<SmsSendRecord, Long> {

    /**
     * Claim a record for a send attempt: CAS PENDING/RETRY → SENDING, bump
     * version and stamp {@code attemptStartedAt}. Losing concurrent claims get
     * {@code false}.
     */
    boolean markSending(Long id, long expectedVersion);

    /**
     * Write back what the first delivery attempt prepared — rendered content, the
     * routed provider, sign name and external template id — so retries replay it.
     * Bumps version; {@code false} if the version did not match.
     */
    boolean markPrepared(SmsSendRecord record, long expectedVersion);

    /**
     * Mark a record SENT with the provider's external message id and how long
     * the provider call took, and preserve the selected provider identity
     * ({@code provider_config_id} + {@code provider_type}).
     */
    void markSent(Long id, long expectedVersion, String providerMessageId,
                  Long providerConfigId, SmsProvider providerType, int sendDurationMs);

    /** Mark a record for retry; bumps retryCount and sets nextRetryAt. */
    boolean markRetry(Long id, long expectedVersion, String errorCode, String errorMessage,
                      LocalDateTime nextRetryAt);

    /**
     * Manually requeue one record for delivery — the SMS mirror of
     * {@code MailSendRecordService#retry}: PENDING / RETRY / FAILED / DEAD_LETTER →
     * RETRY, due now; SENT / SENDING rejected.
     */
    boolean retry(Long id);

    /**
     * Requeue the selected records the way {@link #retry} does, skipping what it
     * would reject instead of failing: {@code SENT} and in-flight {@code SENDING}
     * records, a record that changed while being requeued, and ids that are not
     * found. Returns how many were requeued.
     */
    int retryByIds(List<Long> ids);

    /** Terminally fail a record. */
    boolean markFailed(Long id, long expectedVersion, String errorCode, String errorMessage);

    /** Move a record to DEAD_LETTER after exhausting retries. */
    boolean markDeadLetter(Long id, long expectedVersion, String errorCode, String errorMessage);
}
