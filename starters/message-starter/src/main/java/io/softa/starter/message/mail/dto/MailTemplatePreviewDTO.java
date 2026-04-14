package io.softa.starter.message.mail.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.Map;

/**
 * Request DTO for previewing a rendered mail template.
 * Renders the template with the given variables and returns the result
 * without actually sending an email.
 */
@Data
@Schema(name = "MailTemplatePreviewDTO")
public class MailTemplatePreviewDTO {

    @NotEmpty
    @Schema(description = "Template code to preview", requiredMode = Schema.RequiredMode.REQUIRED)
    private String code;

    @Schema(description = "Variables to substitute into the template placeholders")
    private Map<String, Object> variables;

    // --- Response fields (populated by the API, not by the caller) ---

    @Schema(description = "Rendered subject line")
    private String renderedSubject;

    @Schema(description = "Rendered HTML body")
    private String renderedBody;
}
