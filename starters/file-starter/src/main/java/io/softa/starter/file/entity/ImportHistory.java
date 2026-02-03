package io.softa.starter.file.entity;

import java.io.Serial;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.entity.AuditableModel;
import io.softa.starter.file.enums.ImportStatus;

/**
 * ImportHistory Model
 */
@Data
@Schema(name = "ImportHistory")
@EqualsAndHashCode(callSuper = true)
public class ImportHistory extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private String id;

    @Schema(description = "Tenant ID")
    private String tenantId;

    @Schema(description = "Template ID")
    private String templateId;

    @Schema(description = "Original File ID")
    private String originalFileId;

    @Schema(description = "File Name")
    private String fileName;

    @Schema(description = "Import Status")
    private ImportStatus status;

    @Schema(description = "Failed File ID")
    private String failedFileId;

    @Schema(description = "Total Rows")
    private Integer totalRows;

    @Schema(description = "Success Rows")
    private Integer successRows;

    @Schema(description = "Failed Rows")
    private Integer failedRows;

    @Schema(description = "Deleted")
    private Boolean deleted;
}