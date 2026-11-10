package io.softa.starter.message.sms.service.impl;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.message.MessageScope;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.starter.message.shared.ErrorClassifier;
import io.softa.starter.message.shared.MonthlyQuotaGuard;
import io.softa.starter.message.shared.RateLimiter;
import io.softa.starter.message.shared.SendRejection;
import io.softa.starter.message.shared.metrics.MessageMetrics;
import io.softa.starter.message.shared.retry.SendFailureHandler;
import io.softa.starter.message.sms.dto.SendSmsDTO;
import io.softa.starter.message.sms.dto.SmsAdapterRequest;
import io.softa.starter.message.sms.dto.SmsSendResult;
import io.softa.starter.message.sms.entity.SmsProviderConfig;
import io.softa.starter.message.sms.entity.SmsSendRecord;
import io.softa.starter.message.sms.entity.SmsTemplate;
import io.softa.starter.message.sms.service.SmsSendRecordService;
import io.softa.starter.message.sms.service.SmsTemplateService;
import io.softa.starter.message.sms.support.SmsAdapterFactory;
import io.softa.starter.message.sms.support.SmsProviderDispatcher;
import io.softa.starter.message.sms.support.SmsRoutingPlanner;
import io.softa.starter.message.sms.support.adapter.SmsProviderAdapter;

/**
 * Delivery execution for SMS: prepare the record on its first attempt, rate-limit, deliver through
 * the provider, and route every failure through {@link SendFailureHandler}.
 * <p>
 * The delivery loop claims a record (PENDING / RETRY → SENDING) before handing it here. The first
 * attempt <b>prepares</b> it — renders the template unless the caller sent text, routes the number to
 * a provider by its country and the template's provider bindings, and charges the monthly quota, last
 * — and writes the result back; retries replay the same provider, sign name and template parameters,
 * so a provider failure never switches providers. Whatever stops preparation (no provider for the
 * country, a missing template, the quota used up) fails the record with a marker code and an ERROR
 * log instead of an exception at the caller.
 */
@Slf4j
@Component
public class SmsDeliveryProcessor {

    @Autowired
    private SmsProviderDispatcher dispatcher;

    @Autowired
    private SmsTemplateService templateService;

    @Autowired
    private SmsRoutingPlanner routingPlanner;

    @Autowired
    private MonthlyQuotaGuard quotaGuard;

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
     * Deliver one record the delivery loop has claimed: it is SENDING at {@code version}, and only
     * this call moves it on. Runs in the record's tenant context.
     */
    public void deliver(SmsSendRecord record, long version) {
        SmsProviderConfig config;
        try {
            if (isPrepared(record)) {
                config = resolveProviderById(record.getProviderConfigId());
            } else {
                config = prepare(record);
                if (!recordService.markPrepared(record, version)) {
                    log.debug("SmsDeliveryProcessor: record id={} changed while it was being prepared; "
                            + "leaving it to the newer state", record.getId());
                    return;
                }
                version++;
            }
        } catch (SendRejection e) {
            handleFailure(record, version, e.code(), e.getMessage(), null);
            return;
        } catch (RuntimeException e) {
            // Not a configuration verdict (a database or cache hiccup): keep the stack and let the
            // retry policy try again.
            log.error("SmsDeliveryProcessor: preparing record id={} failed unexpectedly", record.getId(), e);
            handleFailure(record, version, null, e.getMessage(), null);
            return;
        }
        send(record, version, config);
    }

    /**
     * Only preparation writes {@code providerType} — a caller cannot set it — so a record that has one
     * was prepared, and its content and provider are fixed.
     */
    private static boolean isPrepared(SmsSendRecord record) {
        return record.getProviderType() != null;
    }

    /** Fix on {@code record} what the first attempt decides: the content and the provider route. */
    private SmsProviderConfig prepare(SmsSendRecord record) {
        MessageScope scope = Boolean.TRUE.equals(record.getPlatformScope())
                ? MessageScope.PLATFORM : MessageScope.TENANT;
        SmsTemplate template = null;
        if (StringUtils.hasText(record.getTemplateCode())) {
            try {
                template = templateService.resolve(record.getTemplateCode(), scope);
            } catch (BusinessException e) {
                throw new SendRejection(ErrorClassifier.TEMPLATE_NOT_RESOLVABLE, e.getMessage());
            }
        }
        // Caller content wins; otherwise the template renders it.
        if (!StringUtils.hasText(record.getContent()) && template != null) {
            Map<String, Object> variables = record.getTemplateVariables() == null
                    ? Map.of() : JsonUtils.jsonNodeToMap(record.getTemplateVariables());
            try {
                record.setContent(templateService.renderContent(template, variables));
            } catch (RuntimeException e) {
                throw new SendRejection(ErrorClassifier.TEMPLATE_NOT_RESOLVABLE,
                        "SMS template '" + template.getCode() + "' failed to render: " + e.getMessage());
            }
        }
        if (!StringUtils.hasText(record.getContent())) {
            throw new SendRejection(ErrorClassifier.CONTENT_INVALID, "No content: the template rendered an empty text.");
        }
        SmsRoutingPlanner.Plan plan;
        try {
            plan = routingPlanner.plan(new SmsRoutingPlanner.RoutingRequest(record.getPhoneNumber(),
                    record.getProviderConfigId(), record.getTemplateCode(), record.getExternalTemplateId(),
                    record.getSignName(), template));
        } catch (BusinessException e) {
            throw new SendRejection(ErrorClassifier.CONFIG_NOT_RESOLVABLE, e.getMessage());
        }
        try {
            quotaGuard.consume("sms", MonthlyQuotaGuard.bucketFor(scope));
        } catch (BusinessException e) {
            throw new SendRejection(ErrorClassifier.MONTHLY_QUOTA_EXCEEDED, e.getMessage());
        }
        SmsProviderConfig config = plan.providerConfig();
        record.setProviderConfigId(config.getId());
        record.setProviderType(config.getProviderType());
        record.setSignName(plan.signName());
        record.setExternalTemplateId(plan.externalTemplateId());
        return config;
    }

    private SmsProviderConfig resolveProviderById(Long providerConfigId) {
        try {
            return dispatcher.resolveProviderById(providerConfigId);
        } catch (BusinessException e) {
            throw new SendRejection(ErrorClassifier.CONFIG_NOT_RESOLVABLE, e.getMessage());
        }
    }

    private void send(SmsSendRecord record, long version, SmsProviderConfig config) {
        SendSmsDTO dto = rebuildDTO(record);
        AttemptResult r = trySend(dto, config, record.getPhoneNumber());
        if (!r.success()) {
            handleFailure(record, version, r.errorCode(), r.errorMessage(), config);
            return;
        }
        if (metrics != null) metrics.sent("sms", providerName(config));
        try {
            recordService.markSent(record.getId(), version, r.providerMessageId(),
                    config.getId(), config.getProviderType(), r.sendDurationMs());
        } catch (RuntimeException e) {
            // Mirror of MailDeliveryProcessor: the provider has accepted the message, so
            // this is not a send failure. The record stays SENDING until the zombie
            // sweeper revives it after stale-seconds — the one re-send at-least-once costs.
            log.error("SmsDeliveryProcessor: SMS accepted by the provider but recording it as sent "
                    + "failed; record id={} stays SENDING (providerMessageId={})",
                    record.getId(), r.providerMessageId(), e);
        }
    }

    private AttemptResult trySend(SendSmsDTO dto, SmsProviderConfig config, String phoneNumber) {
        RateLimiter.Outcome quota = rateLimiter.tryAcquire("sms", config.getId(),
                config.getDailySendLimit(), config.getRateLimitPerMinute());
        if (!quota.ok()) {
            return new AttemptResult(false, null, "QUOTA_EXCEEDED",
                    "Rate limit: " + quota.name(), 0);
        }
        try {
            SmsProviderAdapter adapter = adapterFactory.getAdapter(config.getProviderType());
            long started = System.nanoTime();
            SmsSendResult result = adapter.send(config, SmsAdapterRequest.from(dto, phoneNumber));
            int sendDurationMs = (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            if (result != null && result.isSuccess()) {
                return new AttemptResult(true, result.getProviderMessageId(), null, null, sendDurationMs);
            }
            String code = result != null ? result.getErrorCode() : null;
            String msg = result != null && result.getErrorMessage() != null
                    ? result.getErrorMessage()
                    : "Provider returned failure";
            return new AttemptResult(false, null, code, msg, sendDurationMs);
        } catch (Exception e) {
            // Known provider failures return a coded result above; anything reaching
            // this catch is unexpected — keep the stack in the log.
            log.error("SmsDeliveryProcessor: unexpected provider failure for phone={}",
                    phoneNumber, e);
            return new AttemptResult(false, null, null, e.getMessage(), 0);
        }
    }

    private void handleFailure(SmsSendRecord record, long version,
                               String errorCode, String errorMessage,
                               SmsProviderConfig config) {
        int currentAttempts = record.getRetryCount() != null ? record.getRetryCount() : 0;
        sendFailureHandler.handle("sms", providerName(config),
                record.getId(), version, currentAttempts, errorCode, errorMessage,
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
        // Retry fidelity: replay the exact signName / externalTemplateId fixed when
        // the record was prepared, not whatever the binding currently says.
        dto.setSignName(record.getSignName());
        dto.setExternalTemplateId(record.getExternalTemplateId());
        return dto;
    }

    /** Internal result holder for a single provider attempt. */
    private record AttemptResult(boolean success, String providerMessageId,
                                 String errorCode, String errorMessage, int sendDurationMs) {}
}
