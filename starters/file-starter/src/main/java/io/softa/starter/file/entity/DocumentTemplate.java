package io.softa.starter.file.entity;

import java.io.Serial;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.entity.AuditableModel;
import io.softa.starter.file.enums.DocumentTemplateType;

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
    private Long id;

    @Schema(description = "Tenant ID")
    private Long tenantId;

    @Schema(description = "Model Name")
    private String modelName;

    @Schema(description = "File Name")
    private String fileName;

    @Schema(description = "Template Type")
    private DocumentTemplateType templateType;

    @Schema(description = "File Template ID")
    private Long fileId;

    @Schema(description = "HTML Template Content")
    private String htmlTemplate;

    @Schema(description = "Convert To PDF")
    private Boolean convertToPdf;

    @Schema(description = "Description")
    private String description;
}