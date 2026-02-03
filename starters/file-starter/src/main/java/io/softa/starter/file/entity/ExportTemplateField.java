package io.softa.starter.file.entity;

import java.io.Serial;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.entity.AuditableModel;

/**
 * ExportTemplateField Model
 */
@Data
@Schema(name = "ExportTemplateField")
@EqualsAndHashCode(callSuper = true)
public class ExportTemplateField extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private String id;

    @Schema(description = "Export Template ID")
    private String templateId;

    @Schema(description = "Field Name")
    private String fieldName;

    @Schema(description = "Custom Header")
    private String customHeader;

    @Schema(description = "Sequence")
    private Integer sequence;

    @Schema(description = "Ignored In File")
    private Boolean ignored;
}