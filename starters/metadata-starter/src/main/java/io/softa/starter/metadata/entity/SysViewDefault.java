package io.softa.starter.metadata.entity;

import io.softa.framework.orm.entity.AuditableModel;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * SysViewDefault Model
 */
@Data
@Schema(name = "SysViewDefault")
@EqualsAndHashCode(callSuper = true)
public class SysViewDefault extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "View ID")
    private Long viewId;

    @Schema(description = "View Code")
    private String viewCode;

    @Schema(description = "Navigation ID")
    private Long navId;

    @Schema(description = "Model Name")
    private String modelName;
}