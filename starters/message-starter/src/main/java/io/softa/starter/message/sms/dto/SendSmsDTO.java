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


    @Schema(description = "Tier-selection policy in multi-tenant deployments. Null/TENANT = the "
            + "current tenant's template and quota bucket. PLATFORM = platform tier only for "
            + "template and quota — for platform-owned SMS (security codes for platform accounts, "
            + "operational alerts). Explicit providerConfigId still wins over the policy.")
    private MessageScope scope;

    /** A templated SMS to one number: the template's code and variables, rendered in {@code scope}'s tier. */
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
