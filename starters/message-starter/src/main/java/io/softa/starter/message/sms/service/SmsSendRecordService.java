package io.softa.starter.message.sms.service;

import java.time.LocalDateTime;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.message.sms.entity.SmsSendRecord;
import io.softa.starter.message.sms.enums.SmsProvider;
import io.softa.starter.message.sms.enums.SmsSendStatus;

/**
 * CRUD service for SMS send records.
 */
public interface SmsSendRecordService extends EntityService<SmsSendRecord, Long> {

    /**
     * CAS a record's status + bump version. Used to claim a record for a
     * send attempt; losing concurrent claims get {@code false}.
     */
    boolean casStatus(Long id, long expectedVersion, SmsSendStatus next);

    /**
     * Mark a record SENT with the provider's external message id, and preserve
     * the selected provider identity ({@code provider_config_id} +
     * {@code provider_type}).
     */
    void markSent(Long id, long expectedVersion, String providerMessageId,
                  Long providerConfigId, SmsProvider providerType);

    /** Mark a record for retry; bumps retryCount and sets nextRetryAt. */
    boolean markRetry(Long id, long expectedVersion, String errorCode, String errorMessage,
                      LocalDateTime nextRetryAt);

    /** Terminally fail a record. */
    boolean markFailed(Long id, long expectedVersion, String errorCode, String errorMessage);

    /** Move a record to DEAD_LETTER after exhausting retries. */
    boolean markDeadLetter(Long id, long expectedVersion, String errorCode, String errorMessage);
}
