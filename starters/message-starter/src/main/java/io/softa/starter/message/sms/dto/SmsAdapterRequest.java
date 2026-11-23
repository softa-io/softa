package io.softa.starter.message.sms.dto;

import java.util.Map;
import lombok.Data;

import io.softa.starter.message.shared.MessageCategory;

/**
 * Provider-level request object passed to
 * {@link io.softa.starter.message.sms.support.adapter.SmsProviderAdapter#send}.
 * <p>
 * Isolates the adapter interface from the API-facing {@link SendSmsDTO},
 * keeping a stable contract between the service layer and provider adapters.
 */
@Data
public class SmsAdapterRequest {

    private String phoneNumber;
    private String content;
    private String externalTemplateId;
    private String signName;
    private Map<String, Object> templateVariables;
    private MessageCategory category;

    public static SmsAdapterRequest from(SendSmsDTO dto, String phoneNumber) {
        SmsAdapterRequest req = new SmsAdapterRequest();
        req.phoneNumber = phoneNumber;
        req.content = dto.getContent();
        req.externalTemplateId = dto.getExternalTemplateId();
        req.signName = dto.getSignName();
        req.templateVariables = dto.getTemplateVariables();
        req.category = dto.getCategory();
        return req;
    }
}
