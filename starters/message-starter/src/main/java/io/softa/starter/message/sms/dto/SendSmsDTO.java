package io.softa.starter.message.sms.dto;

import java.util.Map;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import io.softa.framework.base.message.MessageScope;
import io.softa.framework.orm.meta.TemplateVariableSetRegistry;

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

    /**
     * The variable set ({@code @TemplateVariableSet}) the variables were taken from, when the sender handed over
     * an object. A template that names a set refuses the object of another one. Set by code only:
     * never read from a request body.
     */
    @JsonIgnore
    @Schema(hidden = true)
    private String variableSetName;

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

    /**
     * A templated SMS filled from a {@code @TemplateVariableSet} object; see
     * {@code SendMailDTO.ofTemplate(List, String, Object, MessageScope)}. A component left null is
     * not sent at all: a provider that fills its own registered template is handed the variables
     * themselves, some by position, and would refuse one more than its template takes.
     *
     * @param variables a record carrying {@code @TemplateVariableSet}
     */
    public static SendSmsDTO ofTemplate(String phoneNumber, String templateCode, Object variables,
                                        MessageScope scope) {
        SendSmsDTO sms = ofTemplate(phoneNumber, templateCode,
                TemplateVariableSetRegistry.toMapOmittingMissing(variables), scope);
        sms.setVariableSetName(TemplateVariableSetRegistry.nameOf(variables));
        return sms;
    }
}
