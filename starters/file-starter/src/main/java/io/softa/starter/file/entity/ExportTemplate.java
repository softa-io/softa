package io.softa.starter.file.entity;

import java.io.Serial;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.entity.AuditableModel;

/**
 * ExportTemplate Model
 */
@Data
@Schema(name = "ExportTemplate")
@EqualsAndHashCode(callSuper = true)
public class ExportTemplate extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "Tenant ID")
    private Long tenantId;

    @Schema(description = "File Name")
    private String fileName;

    @Schema(description = "Sheet Name")
    private String sheetName;

    @Schema(description = "Model Name")
    private String modelName;

    @Schema(description = "File Template ID")
    private Long fileId;

    @Schema(description = "Filters")
    private Filters filters;

    @Schema(description = "Orders")
    private Orders orders;

    @Schema(description = "Custom Export Handler")
    private String customHandler;

    @Schema(description = "Enable Transpose")
    private Boolean enableTranspose;
}