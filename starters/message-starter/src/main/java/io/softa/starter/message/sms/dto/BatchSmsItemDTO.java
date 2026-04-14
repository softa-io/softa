package io.softa.starter.message.sms.dto;

import jakarta.validation.constraints.NotBlank;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Map;

/**
 * Per-recipient item in a differentiated batch SMS send.
 * <p>
 * Each item specifies a single recipient phone number with its own content
 * or template variables, enabling different messages per recipient in a single batch.
 * <p>
 * Content resolution priority:
 * <ol>
 *   <li>If {@code content} is set, it is used directly (pre-rendered)</li>
 *   <li>Otherwise, the parent {@link SendSmsDTO#getTemplateCode()} is used
 *       and rendered with this item's {@code templateVariables}</li>
 * </ol>
 */
@Data
@Schema(name = "BatchSmsItemDTO")
public class BatchSmsItemDTO {

    @NotBlank
    @Schema(description = "Recipient phone number (E.164 format)", requiredMode = Schema.RequiredMode.REQUIRED)
    private String phoneNumber;

    @Schema(description = "Direct text content for this recipient (overrides template rendering)")
    private String content;

    @Schema(description = "Per-recipient template placeholder variables (used with parent templateCode)")
    private Map<String, Object> templateVariables;
}

