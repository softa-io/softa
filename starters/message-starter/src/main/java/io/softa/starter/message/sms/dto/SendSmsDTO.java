package io.softa.starter.message.sms.dto;

import java.util.Map;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

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

}
