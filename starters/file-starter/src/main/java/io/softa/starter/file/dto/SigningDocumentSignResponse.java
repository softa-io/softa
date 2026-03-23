package io.softa.starter.file.dto;

import java.time.OffsetDateTime;
import io.swagger.v3.oas.annotations.media.Schema;

import io.softa.framework.orm.dto.FileInfo;

@Schema(name = "SigningDocumentSignResponse")
public record SigningDocumentSignResponse(
        @Schema(description = "Signing document ID.") Long signingDocumentId,
        @Schema(description = "Signing document status.") String status,
        @Schema(description = "Signed PDF file info.") FileInfo signedFile,
        @Schema(description = "Signature image file info.") FileInfo signatureImageFile,
        @Schema(description = "Server-side signed time.") OffsetDateTime signedAt,
        @Schema(description = "Evidence ID.") String evidenceId
) {
}
