package io.softa.starter.file.dto;

import java.time.OffsetDateTime;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "SignatureEvidenceDto")
public record SignatureEvidenceDto(
        @Schema(description = "Signature method. Default: DRAW.") String signatureMethod,
        @Schema(description = "Client-side signed time.") OffsetDateTime clientSignedAt,
        @Schema(description = "Client-side timezone.") String clientTimeZone,
        @Schema(description = "Whether consent was accepted.") Boolean consentAccepted,
        @Schema(description = "Consent text version.") String consentTextVersion,
        @Schema(description = "Signer display name from client.") String signerDisplayName,
        @Schema(description = "Signer remark.") String signerRemark,
        @Schema(description = "Client user agent supplement.") String userAgent,
        @Schema(description = "Client device ID.") String deviceId,
        @Schema(description = "Signature canvas width.") Integer canvasWidth,
        @Schema(description = "Signature canvas height.") Integer canvasHeight
) {
}
