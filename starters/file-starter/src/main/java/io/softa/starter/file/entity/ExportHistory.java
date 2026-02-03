package io.softa.starter.file.entity;

import java.io.Serial;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.entity.AuditableModel;

/**
 * ExportHistory Model
 */
@Data
@Schema(name = "ExportHistory")
@EqualsAndHashCode(callSuper = true)
public class ExportHistory extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private String id;

    @Schema(description = "Tenant ID")
    private String tenantId;

    @Schema(description = "Template ID")
    private String templateId;

    @Schema(description = "Exported File ID")
    private String exportedFileId;

    @Schema(description = "Deleted")
    private Boolean deleted;
}