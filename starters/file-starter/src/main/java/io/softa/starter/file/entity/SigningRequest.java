package io.softa.starter.file.entity;

import java.io.Serial;
import java.time.LocalDateTime;
import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.entity.AuditableModel;
import io.softa.starter.file.enums.SigningRequestStatus;

/**
 * SigningRequest Model
 */
@Data
@Schema(name = "SigningRequest")
@EqualsAndHashCode(callSuper = true)
public class SigningRequest extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "Tenant ID")
    private String tenantId;

    @Schema(description = "Model Name")
    private String modelName;

    @Schema(description = "title")
    private String title;

    @Schema(description = "Code")
    private String code;

    @Schema(description = "Status")
    private SigningRequestStatus status;

    @Schema(description = "Recipient User")
    private Long recipient;

    @Schema(description = "Expires Time")
    private LocalDateTime expiresTime;

    @Schema(description = "Signing Documents")
    private List<SigningDocument> signingDocuments;
}