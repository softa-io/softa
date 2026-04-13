package io.softa.starter.file.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request DTO for generating a preview PDF from raw HTML.
 */
@Data
@Schema(name = "DocumentPreviewRequest")
public class DocumentPreviewRequest {

    @NotBlank
    @Schema(description = "HTML body used to generate the preview PDF.", requiredMode = Schema.RequiredMode.REQUIRED)
    private String htmlBody;
}
