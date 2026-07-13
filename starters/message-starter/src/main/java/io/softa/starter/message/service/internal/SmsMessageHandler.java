package io.softa.starter.message.service.internal;

import java.util.Collections;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.framework.base.exception.BusinessException;
import io.softa.starter.message.mq.TopicRoute;
import io.softa.starter.message.mq.outbox.OutboxRecordWriter;
import io.softa.starter.message.sms.dto.SendSmsDTO;
import io.softa.starter.message.sms.entity.SmsProviderConfig;
import io.softa.starter.message.sms.entity.SmsSendRecord;
import io.softa.starter.message.sms.entity.SmsTemplate;
import io.softa.starter.message.sms.enums.SmsDeliveryStatus;
import io.softa.starter.message.sms.enums.SmsSendStatus;
import io.softa.starter.message.sms.service.SmsSendRecordService;
import io.softa.starter.message.sms.service.SmsTemplateService;
import io.softa.starter.message.sms.service.impl.SmsDeliveryProcessor;
import io.softa.starter.message.sms.support.SmsRoutingPlanner;

/**
 * Internal acceptance handler for one SMS message. The public
 * {@code MessageService} owns single/batch orchestration; this handler validates
 * and renders one request without mutating the caller's DTO, resolves its
 * provider route, and persists one {@code SmsSendRecord (PENDING)} + outbox row.
 * <p>
 * Delivery execution (CAS claim, rate-limit, provider call, retry / DLQ) lives
 * in {@link SmsDeliveryProcessor}, driven by the broker consumers; callers
 * never block on the provider HTTP round-trip.
 * <p>
 * Normalization contract (single funnel):
 * <ol>
 *   <li>Resolve the template once when {@code templateCode} is set.</li>
 *   <li>Render content wherever the caller didn't supply it (caller values
 *       always win over template values).</li>
 *   <li>Validate: one recipient, and non-empty content after
 *       rendering — a template-only request without a resolvable template or
 *       content is rejected here instead of persisting an unsendable record.</li>
 * </ol>
 * Provider routing is resolved per recipient before persistence; retries
 * replay the same provider / template parameters.
 */
@Component
@RequiredArgsConstructor
final class SmsMessageHandler {

    private final SmsSendRecordService recordService;

    private final SmsTemplateService templateService;

    private final SmsRoutingPlanner routingPlanner;

    private final OutboxRecordWriter outboxRecordWriter;

    Long send(SendSmsDTO dto) {
        if (!StringUtils.hasText(dto.getPhoneNumber())) {
            throw new BusinessException("SMS send rejected: phoneNumber is required");
        }
        SmsTemplate template = StringUtils.hasText(dto.getTemplateCode())
                ? templateService.resolve(dto.getTemplateCode())
                : null;
        String content = resolveContent(dto.getContent(), template, dto.getTemplateVariables());
        SmsRoutingPlanner.RoutingRequest routingRequest = new SmsRoutingPlanner.RoutingRequest(
                dto.getPhoneNumber(), dto.getProviderConfigId(), dto.getTemplateCode(),
                dto.getExternalTemplateId(), dto.getSignName(), template);
        SmsRoutingPlanner.Plan plan = routingPlanner.plan(routingRequest);
        ResolvedSms message = new ResolvedSms(
                dto.getPhoneNumber(), content, dto.getTemplateCode(), plan);
        return enqueueForAsyncSend(message);
    }

    /**
     * Caller content wins; otherwise render the template. A request with
     * neither is unsendable and rejected before anything is persisted.
     */
    private String resolveContent(String callerContent, SmsTemplate template,
                                  Map<String, Object> variables) {
        if (StringUtils.hasText(callerContent)) {
            return callerContent;
        }
        if (template != null) {
            String rendered = templateService.renderContent(template,
                    variables != null ? variables : Collections.emptyMap());
            if (StringUtils.hasText(rendered)) {
                return rendered;
            }
        }
        throw new BusinessException(
                "SMS send rejected: no content — provide content or a templateCode that renders one");
    }

    // ------------------------------------------------------------------
    // Persistence — route, then PENDING record + outbox row atomically
    // ------------------------------------------------------------------

    /**
     * Persist PENDING record + outbox row inside a single transaction. The
     * transaction boundary lives on {@link OutboxRecordWriter} so it is crossed
     * through the Spring proxy (a {@code @Transactional} method on this bean
     * would be bypassed by self-invocation).
     */
    private Long enqueueForAsyncSend(ResolvedSms message) {
        return outboxRecordWriter.persistAndEnqueue(
                () -> {
                    SmsSendRecord record = buildRecord(message);
                    return recordService.createOne(record);
                },
                "SmsSendRecord", TopicRoute.SMS_SEND);
    }

    private SmsSendRecord buildRecord(ResolvedSms message) {
        SmsRoutingPlanner.Plan plan = message.plan();
        SmsProviderConfig config = plan.providerConfig();
        SmsSendRecord record = new SmsSendRecord();
        record.setProviderConfigId(config.getId());
        record.setProviderType(config.getProviderType());
        record.setPhoneNumber(message.phoneNumber());
        record.setTemplateCode(message.templateCode());
        record.setContent(message.content());
        record.setSignName(plan.signName());
        record.setExternalTemplateId(plan.externalTemplateId());
        record.setStatus(SmsSendStatus.PENDING);
        record.setRetryCount(0);
        record.setVersion(0L);
        record.setDeliveryStatus(SmsDeliveryStatus.UNKNOWN);
        return record;
    }

    private record ResolvedSms(String phoneNumber,
                               String content,
                               String templateCode,
                               SmsRoutingPlanner.Plan plan) {}
}
