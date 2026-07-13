package io.softa.starter.message.sms.service.impl;

import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.stereotype.Service;

import io.softa.starter.message.shared.AbstractCasSendRecordServiceImpl;
import io.softa.starter.message.sms.entity.SmsSendRecord;
import io.softa.starter.message.sms.enums.SmsProvider;
import io.softa.starter.message.sms.enums.SmsSendStatus;
import io.softa.starter.message.sms.service.SmsSendRecordService;

/**
 * Implementation of {@link SmsSendRecordService}.
 * <p>
 * The generic status-transition CAS is inherited from
 * {@link AbstractCasSendRecordServiceImpl}; this class adds the SMS-specific
 * {@code markSent} that records the winning provider identity.
 */
@Service
public class SmsSendRecordServiceImpl extends AbstractCasSendRecordServiceImpl<SmsSendRecord>
        implements SmsSendRecordService {

    @Override
    public boolean casStatus(Long id, long expectedVersion, SmsSendStatus next) {
        return transitionStatus(id, expectedVersion, next);
    }

    @Override
    public void markSent(Long id, long expectedVersion, String providerMessageId,
                         Long providerConfigId, SmsProvider providerType) {
        Map<String, Object> patch = versionedPatch(id, expectedVersion);
        patch.put("status", SmsSendStatus.SENT);
        patch.put("sentAt", LocalDateTime.now());
        patch.put("providerMessageId", providerMessageId);
        if (providerConfigId != null) {
            patch.put("providerConfigId", providerConfigId);
        }
        if (providerType != null) {
            patch.put("providerType", providerType);
        }
        patch.put("errorCode", null);
        patch.put("errorMessage", null);
        updateVersioned(patch);
    }

    @Override
    public boolean markRetry(Long id, long expectedVersion, String errorCode, String errorMessage,
                             LocalDateTime nextRetryAt) {
        int retryCount = getById(id)
                .map(SmsSendRecord::getRetryCount)
                .orElse(0);
        return markRetryStatus(id, expectedVersion, SmsSendStatus.RETRY,
                retryCount + 1, errorCode, errorMessage, nextRetryAt);
    }

    @Override
    public boolean markFailed(Long id, long expectedVersion, String errorCode, String errorMessage) {
        return markTerminalStatus(id, expectedVersion, SmsSendStatus.FAILED, errorCode, errorMessage);
    }

    @Override
    public boolean markDeadLetter(Long id, long expectedVersion, String errorCode, String errorMessage) {
        return markTerminalStatus(id, expectedVersion, SmsSendStatus.DEAD_LETTER, errorCode, errorMessage);
    }
}
