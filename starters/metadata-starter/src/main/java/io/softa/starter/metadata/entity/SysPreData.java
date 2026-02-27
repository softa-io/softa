package io.softa.starter.metadata.entity;

import java.io.Serial;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.entity.AuditableModel;

/**
 * SysPreData Model
 */
@Data
@Schema(name = "SysPreData")
@EqualsAndHashCode(callSuper = true)
public class SysPreData extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "Model Name")
    private String model;

    @Schema(description = "Predefined ID")
    private String preId;

    @Schema(description = "Row Data ID")
    private String rowId;

    @Schema(description = "Frozen")
    private Boolean frozen;

    @Schema(description = "Tenant ID")
    private Long tenantId;
}