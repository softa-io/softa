package io.softa.starter.file.entity;

import java.io.Serial;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.entity.AuditableModel;

/**
 * DocumentTemplate Model
 */
@Data
@Schema(name = "DocumentTemplate")
@EqualsAndHashCode(callSuper = true)
public class DocumentTemplate extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private String id;

    @Schema(description = "Tenant ID")
    private String tenantId;

    @Schema(description = "Model Name")
    private String modelName;

    @Schema(description = "File Name")
    private String fileName;

    @Schema(description = "File Template ID")
    private String fileId;

    @Schema(description = "Convert To PDF")
    private Boolean convertToPdf;
}