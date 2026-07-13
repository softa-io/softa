package io.softa.starter.message.sms.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.softa.starter.message.mq.TopicRoute;
import io.softa.starter.message.shared.RateLimiter;
import io.softa.starter.message.shared.metrics.MessageMetrics;
import io.softa.starter.message.shared.retry.SendFailureHandler;
import io.softa.starter.message.sms.dto.SendSmsDTO;
import io.softa.starter.message.sms.dto.SmsAdapterRequest;
import io.softa.starter.message.sms.dto.SmsSendResult;
import io.softa.starter.message.sms.entity.SmsProviderConfig;
import io.softa.starter.message.sms.entity.SmsSendRecord;
import io.softa.starter.message.sms.enums.SmsSendStatus;
import io.softa.starter.message.sms.service.SmsSendRecordService;
import io.softa.starter.message.sms.support.SmsAdapterFactory;
import io.softa.starter.message.sms.support.SmsProviderDispatcher;
import io.softa.starter.message.sms.support.adapter.SmsProviderAdapter;

/**
 * Consumer-side delivery execution for SMS: claim the record via CAS
 * (PENDING/RETRY → SENDING), rate-limit, deliver through the provider that was
 * selected at enqueue time, and route every failure mode through
 * {@link SendFailureHandler}.
 * <p>
 * Deliberately separate from the public {@code MessageService}: the service is the
 * <i>acceptance</i> side (normalize request → route → persist PENDING + outbox
 * row and return), this processor is the <i>execution</i> side driven by the
 * broker consumers and the manual-retry API. Duplicate broker deliveries are
 * rejected by the CAS claim, so the processor is safe to invoke any number of
 * times per record. Retries replay the exact provider / template parameters
 * stored on the record; a provider failure never switches providers.
 */
@Slf4j
@Component
public class SmsDeliveryProcessor {

    @Autowired
    private SmsProviderDispatcher dispatcher;

    @Autowired
    private SmsAdapterFactory adapterFactory;

    @Autowired
    private SmsSendRecordService recordService;

    @Autowired(required = false)
    private MessageMetrics metrics;

    @Autowired
    private RateLimiter rateLimiter;

    @Autowired
    private SendFailureHandler sendFailureHandler;

    /**
     * Load, claim, and deliver one send record. No-ops when the record is
     * missing or already terminal / in flight (duplicate delivery).
     */
    public void process(Long recordId) {
        SmsSendRecord record = recordService.getById(recordId).orElse(null);
        if (record == null) {
            log.warn("SmsDeliveryProcessor: record not found id={} (duplicate or TTL-evicted)", recordId);
            return;
        }
        SmsSendStatus s = record.getStatus();
        if (s != SmsSendStatus.PENDING && s != SmsSendStatus.RETRY) {
            log.debug("SmsDeliveryProcessor: record id={} already in terminal/sending state {}, ignoring",
                    recordId, s);
            return;
        }
        long expectedVersion = record.getVersion() != null ? record.getVersion() : 0L;

        if (!recordService.casStatus(record.getId(), expectedVersion, SmsSendStatus.SENDING)) {
            log.debug("SmsDeliveryProcessor: CAS miss for id={} v={}, treating as duplicate",
                    record.getId(), expectedVersion);
            return;
        }
        attemptSingleProvider(record, expectedVersion + 1);
    }

    private void attemptSingleProvider(SmsSendRecord record, long sendingVersion) {
        SmsProviderConfig config;
        try {
            config = dispatcher.resolveProviderById(record.getProviderConfigId());
        } catch (Exception e) {
            handleFailure(record, sendingVersion, null,
                    "Config not resolvable: " + e.getMessage(), null);
            return;
        }
        SendSmsDTO dto = rebuildDTO(record);
        AttemptResult r = trySend(dto, config, record.getPhoneNumber());
        if (r.success()) {
            recordService.markSent(record.getId(), sendingVersion, r.providerMessageId(),
                    config.getId(), config.getProviderType());
            if (metrics != null) metrics.sent("sms", providerName(config));
        } else {
            handleFailure(record, sendingVersion, r.errorCode(), r.errorMessage(), config);
        }
    }

    private AttemptResult trySend(SendSmsDTO dto, SmsProviderConfig config, String phoneNumber) {
        RateLimiter.Outcome quota = rateLimiter.tryAcquire("sms", config.getId(),
                config.getDailySendLimit(), config.getRateLimitPerMinute());
        if (!quota.ok()) {
            return new AttemptResult(false, null, "QUOTA_EXCEEDED",
                    "Rate limit: " + quota.name());
        }
        try {
            SmsProviderAdapter adapter = adapterFactory.getAdapter(config.getProviderType());
            SmsSendResult result = adapter.send(config, SmsAdapterRequest.from(dto, phoneNumber));
            if (result != null && result.isSuccess()) {
                return new AttemptResult(true, result.getProviderMessageId(), null, null);
            }
            String code = result != null ? result.getErrorCode() : null;
            String msg = result != null && result.getErrorMessage() != null
                    ? result.getErrorMessage()
                    : "Provider returned failure";
            return new AttemptResult(false, null, code, msg);
        } catch (Exception e) {
            return new AttemptResult(false, null, null, e.getMessage());
        }
    }

    private void handleFailure(SmsSendRecord record, long expectedVersion,
                               String errorCode, String errorMessage,
                               SmsProviderConfig config) {
        int currentAttempts = record.getRetryCount() != null ? record.getRetryCount() : 0;
        sendFailureHandler.handle("sms", providerName(config), TopicRoute.SMS_SEND, "SmsSendRecord",
                record.getId(), expectedVersion, currentAttempts, errorCode, errorMessage,
                new SendFailureHandler.RecordTransitions(
                        recordService::markRetry, recordService::markFailed, recordService::markDeadLetter));
    }

    private static String providerName(SmsProviderConfig config) {
        return config != null && config.getProviderType() != null
                ? config.getProviderType().name().toLowerCase()
                : null;
    }

    private SendSmsDTO rebuildDTO(SmsSendRecord record) {
        SendSmsDTO dto = new SendSmsDTO();
        dto.setPhoneNumber(record.getPhoneNumber());
        dto.setContent(record.getContent());
        dto.setTemplateCode(record.getTemplateCode());
        dto.setProviderConfigId(record.getProviderConfigId());
        // Retry fidelity: replay the exact signName / externalTemplateId used
        // at first send, not whatever the binding currently says.
        dto.setSignName(record.getSignName());
        dto.setExternalTemplateId(record.getExternalTemplateId());
        return dto;
    }

    /** Internal result holder for a single provider attempt. */
    private record AttemptResult(boolean success, String providerMessageId,
                                 String errorCode, String errorMessage) {}
}
