package io.softa.starter.message.service.internal;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.message.MessageScope;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.starter.message.shared.TenantScopes;
import io.softa.starter.message.sms.dto.SendSmsDTO;
import io.softa.starter.message.sms.entity.SmsSendRecord;
import io.softa.starter.message.sms.enums.SmsDeliveryStatus;
import io.softa.starter.message.sms.enums.SmsSendStatus;
import io.softa.starter.message.sms.service.SmsSendRecordService;
import io.softa.starter.message.sms.service.impl.SmsDeliveryProcessor;
import io.softa.starter.message.sms.support.SmsProviderDispatcher;

/**
 * Internal acceptance handler for one SMS message. The counterpart of {@code MailMessageHandler}:
 * acceptance checks only what the caller got wrong — a number that parses as E.164, content or a
 * template to render it from — and stores the request as it was made in a PENDING
 * {@link SmsSendRecord}, in the caller's transaction.
 * <p>
 * Rendering the template, routing the number to a provider and charging the monthly quota happen at
 * the first delivery attempt, in {@link SmsDeliveryProcessor}; a request that fails there still has
 * its record, FAILED with the reason. Nothing is written before validation passes.
 */
@Component
@RequiredArgsConstructor
final class SmsMessageHandler {

    private final SmsSendRecordService recordService;

    private final SmsProviderDispatcher providerDispatcher;

    Long send(SendSmsDTO dto) {
        return insert(accept(dto));
    }

    /** Accept every message before writing any: one invalid message rejects the whole batch. */
    List<Long> sendBatch(List<SendSmsDTO> batch) {
        List<SmsSendRecord> records = batch.stream().map(this::accept).toList();
        return records.stream().map(this::insert).toList();
    }

    private Long insert(SmsSendRecord record) {
        return TenantScopes.inRecordOwnerContext(record.getPlatformScope(), () -> recordService.createOne(record));
    }

    /** Validate one request and build its PENDING record, without writing anything. */
    SmsSendRecord accept(SendSmsDTO dto) {
        if (!StringUtils.hasText(dto.getPhoneNumber())) {
            throw new BusinessException("SMS send rejected: phoneNumber is required");
        }
        // Parses the number only (no configuration read): a bare national number, or one that is not
        // a number at all, is the caller's mistake and never gets a record.
        providerDispatcher.resolveRegion(dto.getPhoneNumber());
        if (!StringUtils.hasText(dto.getContent()) && !StringUtils.hasText(dto.getTemplateCode())) {
            throw new BusinessException(
                    "SMS send rejected: no content — provide content or a templateCode that renders one");
        }

        SmsSendRecord record = new SmsSendRecord();
        record.setPlatformScope(dto.getScope() == MessageScope.PLATFORM);
        record.setTraceId(ContextHolder.getContext().getTraceId());
        record.setPhoneNumber(dto.getPhoneNumber());
        record.setTemplateCode(StringUtils.hasText(dto.getTemplateCode()) ? dto.getTemplateCode() : null);
        record.setTemplateVariables(CollectionUtils.isEmpty(dto.getTemplateVariables())
                ? null : JsonUtils.objectToJsonNode(dto.getTemplateVariables()));
        record.setContent(StringUtils.hasText(dto.getContent()) ? dto.getContent() : null);
        // Caller overrides; routing at the first attempt fills in whatever they leave open.
        record.setProviderConfigId(dto.getProviderConfigId());
        record.setSignName(dto.getSignName());
        record.setExternalTemplateId(dto.getExternalTemplateId());
        record.setStatus(SmsSendStatus.PENDING);
        record.setRetryCount(0);
        record.setVersion(0L);
        record.setDeliveryStatus(SmsDeliveryStatus.UNKNOWN);
        return record;
    }
}
