package io.softa.starter.message.sms.dto;

import java.util.Map;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import io.softa.framework.base.message.MessageScope;

/**
 * Request payload for one SMS recipient. Independent messages use
 * {@code MessageService.sendSmsBatch(List)}.
 */
@Data
@Schema(name = "SendSmsDTO")
public class SendSmsDTO {

    @Schema(description = "Single recipient phone number (E.164 format)")
    private String phoneNumber;

    @Schema(description = "Direct text content (mutually exclusive with templateCode)")
    private String content;

    @Schema(description = "Template code for template-based sending (e.g. VERIFY_CODE)")
    private String templateCode;

    @Schema(description = "Template placeholder variables")
    private Map<String, Object> templateVariables;

    @Schema(description = "Explicit provider config ID; null = auto-resolved via SmsProviderDispatcher")
    private Long providerConfigId;

    @Schema(description = "SMS signature override (from template or config if not set)")
    private String signName;

    @Schema(description = "External template ID override (from template if not set)")
    private String externalTemplateId;

    @Schema(description = "Null/TENANT or PLATFORM, kept for symmetry with mail and recorded on the send. "
            + "SMS templates and providers are platform-owned either way, and the send is filed under — and "
            + "draws on the monthly quota of — the tenant it is made for: the current tenant, or the "
            + "platform when there is none. To send for another tenant, call inside "
            + "ContextUtils.inTenantContext(tenantId, ...).")
    private MessageScope scope;

    /** A templated SMS to one number: the template's code and variables, sent in {@code scope}. */
    public static SendSmsDTO ofTemplate(String phoneNumber, String templateCode, Map<String, Object> variables,
                                        MessageScope scope) {
        SendSmsDTO sms = new SendSmsDTO();
        sms.setPhoneNumber(phoneNumber);
        sms.setTemplateCode(templateCode);
        sms.setTemplateVariables(variables);
        sms.setScope(scope);
        return sms;
    }
}
