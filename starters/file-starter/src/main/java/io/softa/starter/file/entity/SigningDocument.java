package io.softa.starter.file.entity;

import java.io.Serial;
import java.time.LocalDateTime;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import tools.jackson.databind.JsonNode;

import io.softa.framework.orm.entity.AuditableModel;
import io.softa.starter.file.enums.SigningDocumentStatus;

/**
 * SigningDocument Model
 */
@Data
@Schema(name = "SigningDocument")
@EqualsAndHashCode(callSuper = true)
public class SigningDocument extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "Tenant ID")
    private String tenantId;

    @Schema(description = "Signing Request ID")
    private Long signingRequestId;

    @Schema(description = "Title")
    private String title;

    @Schema(description = "Sequence")
    private Integer sequence;

    @Schema(description = "Status")
    private SigningDocumentStatus status;

    @Schema(description = "Document Template")
    private Long templateId;

    @Schema(description = "Sign Slot Code")
    private String signSlotCode;

    @Schema(description = "Signed Image File")
    private Long signedImageId;

    @Schema(description = "Signed PDF File")
    private Long signedPdfId;

    @Schema(description = "Signer User ID")
    private Long signerUserId;

    @Schema(description = "Evidence ID")
    private String evidenceId;

    @Schema(description = "Signature evidence JSON")
    private JsonNode signatureEvidence;

    @Schema(description = "Signed Time")
    private LocalDateTime signedTime;
}
