package io.softa.starter.message.sms.dto;

import java.util.List;
import java.util.Map;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request payload for sending an SMS.
 */
@Data
@Schema(name = "SendSmsDTO")
public class SendSmsDTO {

    private static final int MAX_BATCH_SIZE = 500;

    @Schema(description = "Single recipient phone number (E.164 format)")
    private String phoneNumber;

    @Schema(description = "Multiple recipient phone numbers for batch send")
    private List<String> phoneNumbers;

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

    @Size(max = MAX_BATCH_SIZE)
    @Schema(description = "Per-recipient differentiated batch items. "
            + "When set, each item can carry its own content or templateVariables. "
            + "Mutually exclusive with phoneNumber/phoneNumbers for batch sends.")
    private List<BatchSmsItemDTO> items;
}
