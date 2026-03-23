package io.softa.starter.file.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;

@Schema(name = "SigningDocumentSignRequest")
public record SigningDocumentSignRequest(
        @Schema(description = "Template-defined sign slot code.") String signSlotCode,
        @Valid
        @Schema(description = "Free placement when no PDF field is available.") SignaturePlacementDto placement,
        @Valid
        @Schema(description = "Client-side signing evidence supplement.") SignatureEvidenceDto evidence,
        @Valid
        @Schema(description = "Signature rendering options.") SignRenderOptionsDto renderOptions
) {
}
