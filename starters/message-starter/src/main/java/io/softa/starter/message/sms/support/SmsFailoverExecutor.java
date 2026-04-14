package io.softa.starter.message.sms.support;

import io.softa.starter.message.sms.dto.SendSmsDTO;
import io.softa.starter.message.sms.dto.SmsAdapterRequest;
import io.softa.starter.message.sms.dto.SmsSendResult;
import io.softa.starter.message.sms.entity.SmsProviderConfig;
import io.softa.starter.message.sms.entity.SmsSendRecord;
import io.softa.starter.message.sms.entity.SmsTemplateProviderBinding;
import io.softa.starter.message.sms.enums.SmsSendStatus;
import io.softa.starter.message.sms.support.adapter.SmsProviderAdapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Encapsulates the failover loop for SMS sending across multiple provider bindings.
 * <p>
 * Iterates bindings in sort order, attempting each provider until one succeeds.
 * On success, updates the record with SENT status. On full failure, returns
 * the last provider config so the caller can handle retry/failure logic.
 */
@Slf4j
@Component
public class SmsFailoverExecutor {

    @Autowired
    private SmsProviderDispatcher dispatcher;

    @Autowired
    private SmsAdapterFactory adapterFactory;

    /**
     * Result of a failover execution.
     */
    public record FailoverResult(boolean success, SmsProviderConfig lastConfig) {}

    /**
     * Execute the failover loop across the binding chain.
     *
     * @param dto         the send DTO (used for binding overrides)
     * @param bindings    ordered list of provider bindings to try
     * @param record      the send record to update on success/failure
     * @param phoneNumber the target phone number
     * @return the result indicating success and the last attempted provider config
     */
    public FailoverResult execute(SendSmsDTO dto, List<SmsTemplateProviderBinding> bindings,
                                  SmsSendRecord record, String phoneNumber) {
        SmsProviderConfig lastConfig = null;

        for (int i = 0; i < bindings.size(); i++) {
            SmsTemplateProviderBinding binding = bindings.get(i);
            SmsProviderConfig config;
            try {
                config = dispatcher.resolveProviderById(binding.getProviderConfigId());
            } catch (Exception e) {
                log.warn("Failover: Could not resolve provider config id={} for binding id={}, skipping: {}",
                        binding.getProviderConfigId(), binding.getId(), e.getMessage());
                continue;
            }
            lastConfig = config;

            SendSmsDTO bindingDto = cloneDtoWithBindingOverrides(dto, binding, config);

            SmsSendResult result = trySend(bindingDto, config, phoneNumber);
            if (result != null && result.isSuccess()) {
                record.setProviderConfigId(config.getId());
                record.setProviderType(config.getProviderType());
                record.setStatus(SmsSendStatus.SENT);
                record.setSentAt(LocalDateTime.now());
                record.setProviderMessageId(result.getProviderMessageId());
                return new FailoverResult(true, config);
            }

            String errorCode = result != null ? result.getErrorCode() : null;
            String errorMsg = result != null && result.getErrorMessage() != null
                    ? result.getErrorMessage() : "Provider returned failure";
            log.warn("Failover: Provider {} (binding sortOrder={}) failed for record id={}: [{}] {}. ({}/{})",
                    config.getProviderType(), binding.getSortOrder(), record.getId(),
                    errorCode, errorMsg, i + 1, bindings.size());

            record.setProviderConfigId(config.getId());
            record.setProviderType(config.getProviderType());
            record.setErrorCode(errorCode);
            record.setErrorMessage(errorMsg);
        }

        if (lastConfig == null) {
            log.warn("Failover: all {} binding(s) failed to resolve provider config — cannot retry",
                    bindings.size());
            record.setErrorCode("PROVIDER_RESOLVE_FAILED");
            record.setErrorMessage("All " + bindings.size() + " provider binding(s) could not be resolved");
            return new FailoverResult(false, null);
        }
        return new FailoverResult(false, lastConfig);
    }

    private SmsSendResult trySend(SendSmsDTO dto, SmsProviderConfig config, String phoneNumber) {
        try {
            SmsProviderAdapter adapter = adapterFactory.getAdapter(config.getProviderType());
            return adapter.send(config, SmsAdapterRequest.from(dto, phoneNumber));
        } catch (Exception e) {
            log.warn("trySend failed for provider {} (configId={}): {}",
                    config.getProviderType(), config.getId(), e.getMessage());
            return null;
        }
    }

    private SendSmsDTO cloneDtoWithBindingOverrides(SendSmsDTO dto,
                                                     SmsTemplateProviderBinding binding,
                                                     SmsProviderConfig config) {
        SendSmsDTO bindingDto = new SendSmsDTO();
        bindingDto.setPhoneNumber(dto.getPhoneNumber());
        bindingDto.setContent(dto.getContent());
        bindingDto.setTemplateCode(dto.getTemplateCode());
        bindingDto.setTemplateVariables(dto.getTemplateVariables());
        bindingDto.setProviderConfigId(config.getId());

        bindingDto.setExternalTemplateId(
                StringUtils.hasText(binding.getExternalTemplateId())
                        ? binding.getExternalTemplateId()
                        : dto.getExternalTemplateId());
        bindingDto.setSignName(
                StringUtils.hasText(binding.getSignName())
                        ? binding.getSignName()
                        : dto.getSignName());

        return bindingDto;
    }
}
