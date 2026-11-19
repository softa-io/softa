package io.softa.starter.message.service.internal;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.context.ContextUtils;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.message.MessageScope;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.base.utils.PhoneUtils;
import io.softa.starter.message.shared.TenantScopes;
import io.softa.starter.message.sms.dto.SendSmsDTO;
import io.softa.starter.message.sms.entity.SmsSendRecord;
import io.softa.starter.message.sms.entity.SmsTemplate;
import io.softa.starter.message.sms.enums.SmsDeliveryStatus;
import io.softa.starter.message.sms.enums.SmsSendStatus;
import io.softa.starter.message.sms.service.SmsSendRecordService;
import io.softa.starter.message.sms.service.SmsTemplateService;
import io.softa.starter.message.sms.service.impl.SmsDeliveryProcessor;
import io.softa.starter.message.sms.support.SmsProviderDispatcher;

/**
 * Internal acceptance handler for one SMS message. The counterpart of {@code MailMessageHandler}:
 * acceptance checks only what the caller got wrong — a number with its country code and the right
 * number of digits for that country, content or a template to render it from — and stores the
 * request in a PENDING {@link SmsSendRecord}, in the caller's transaction, with the number in E.164
 * ({@code +6591234567}) whatever separators it was typed with.
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

    private final SmsTemplateService templateService;

    Long send(SendSmsDTO dto) {
        return insert(accept(dto));
    }

    /** Accept every message before writing any: one invalid message rejects the whole batch. */
    List<Long> sendBatch(List<SendSmsDTO> batch) {
        List<SmsSendRecord> records = batch.stream().map(this::accept).toList();
        return records.stream().map(this::insert).toList();
    }

    private Long insert(SmsSendRecord record) {
        return recordService.createOne(record);
    }

    /**
     * Whether an SMS can go to {@code phoneNumber} now: it is a number {@link #accept} takes, and an
     * enabled route serves its country. Asked on a person's own request (a screen about to text them
     * a code), so the platform's routing tables are read without that person's permissions — a tenant
     * role holds none on them — and only the yes or no leaves here.
     */
    boolean canSend(String phoneNumber) {
        try {
            String e164 = PhoneUtils.toE164(phoneNumber);
            return ContextUtils.withoutPermissionChecks(() -> !providerDispatcher.resolveProviders(e164).isEmpty());
        } catch (BusinessException e) {
            return false;
        }
    }

    /** Validate one request and build its PENDING record, without writing anything. */
    SmsSendRecord accept(SendSmsDTO dto) {
        if (!StringUtils.hasText(dto.getPhoneNumber())) {
            throw new BusinessException("SMS send rejected: phoneNumber is required");
        }
        // The number only, no configuration read: one without its country code, with letters, of the
        // wrong length for its country, or of a country the bundled ranges cannot place, is the
        // caller's mistake and never gets a record. A country without a route is configuration, and
        // fails at the first attempt, where adding the route and retrying recovers it.
        String phoneNumber = PhoneUtils.toE164(dto.getPhoneNumber());
        providerDispatcher.resolveRegion(phoneNumber);
        if (!StringUtils.hasText(dto.getContent()) && !StringUtils.hasText(dto.getTemplateCode())) {
            throw new BusinessException(
                    "SMS send rejected: no content — provide content or a templateCode that renders one");
        }
        requireMatchingVariables(dto);

        SmsSendRecord record = new SmsSendRecord();
        record.setPlatformScope(dto.getScope() == MessageScope.PLATFORM);
        // The table is not multi-tenant, so the owner is written here, not stamped by the ORM: the
        // tenant the send is made for whatever its scope (see MailMessageHandler), the platform when
        // no tenant can be named.
        record.setTenantId(TenantScopes.currentTenantOrPlatform());
        record.setTraceId(ContextHolder.getContext().getTraceId());
        record.setPhoneNumber(phoneNumber);
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

    /**
     * An object of a {@code @TemplateVariables} set, sent through a template that names a set, must
     * be of that set — the wrong one would go out with its {@code {{ names }}} unfilled. A template
     * that cannot be found is left to the first delivery attempt, which records why.
     */
    private void requireMatchingVariables(SendSmsDTO dto) {
        if (!StringUtils.hasText(dto.getVariablesName()) || !StringUtils.hasText(dto.getTemplateCode())) {
            return;
        }
        SmsTemplate template;
        try {
            // Platform configuration: not something the person whose action sends the SMS holds a permission on.
            template = ContextUtils.withoutPermissionChecks(() -> templateService.resolve(dto.getTemplateCode()));
        } catch (BusinessException e) {
            return;
        }
        String set = template == null || !StringUtils.hasText(template.getVariablesName())
                ? null : template.getVariablesName().trim();
        if (set != null && !set.equals(dto.getVariablesName().trim())) {
            throw new BusinessException("SMS send rejected: template ''{0}'' is written against the template "
                    + "variables ''{1}'', but was sent ''{2}''.", template.getCode(), set, dto.getVariablesName().trim());
        }
    }
}
