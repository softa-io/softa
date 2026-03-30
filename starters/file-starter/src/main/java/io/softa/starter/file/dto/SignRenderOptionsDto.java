package io.softa.starter.file.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "SignRenderOptionsDto")
public record SignRenderOptionsDto(
        @Schema(description = "Flatten PDF after signing. Default: true.") Boolean flattenToPdf,
        @Schema(description = "Keep uploaded signature image. Default: true.") Boolean keepSignatureImage,
        @Schema(description = "Image scale mode. Default: FIT.") String imageScaleMode
) {
}
