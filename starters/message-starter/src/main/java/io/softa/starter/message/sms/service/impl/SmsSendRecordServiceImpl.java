package io.softa.starter.message.sms.service.impl;

import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.stereotype.Service;

import io.softa.framework.base.exception.BusinessException;
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

    private static final String MANUAL_RETRY_CODE = "MANUAL_RETRY";
    private static final String MANUAL_RETRY_MESSAGE = "Manually requeued for delivery";

    @Override
    public boolean retry(Long id) {
        SmsSendRecord record = getById(id).orElseThrow(() ->
                new BusinessException("SMS send record {0} does not exist.", id));
        SmsSendStatus status = record.getStatus();
        if (status == SmsSendStatus.SENT) {
            throw new BusinessException("This SMS was already sent — there is nothing to retry.");
        }
        if (status == SmsSendStatus.SENDING) {
            throw new BusinessException("This SMS is being sent right now. If it is stuck, "
                    + "stale SENDING records are requeued automatically after the zombie window.");
        }
        // See MailSendRecordServiceImpl#retry — same transition, same one-new-attempt semantics.
        long expectedVersion = record.getVersion() != null ? record.getVersion() : 0L;
        boolean ok = markRetry(id, expectedVersion, MANUAL_RETRY_CODE, MANUAL_RETRY_MESSAGE, LocalDateTime.now());
        if (!ok) {
            throw new BusinessException("The record changed concurrently — refresh and retry again.");
        }
        return true;
    }

    @Override
    public boolean markSending(Long id, long expectedVersion) {
        return claimForAttempt(id, expectedVersion, SmsSendStatus.SENDING);
    }

    @Override
    public boolean markPrepared(SmsSendRecord record, long expectedVersion) {
        Map<String, Object> patch = versionedPatch(record.getId(), expectedVersion);
        patch.put("content", record.getContent());
        patch.put("providerConfigId", record.getProviderConfigId());
        patch.put("providerType", record.getProviderType());
        patch.put("signName", record.getSignName());
        patch.put("externalTemplateId", record.getExternalTemplateId());
        return updateVersioned(patch);
    }

    @Override
    public void markSent(Long id, long expectedVersion, String providerMessageId,
                         Long providerConfigId, SmsProvider providerType, int sendDurationMs) {
        Map<String, Object> patch = versionedPatch(id, expectedVersion);
        patch.put("status", SmsSendStatus.SENT);
        patch.put("sentAt", LocalDateTime.now());
        patch.put("sendDurationMs", sendDurationMs);
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
