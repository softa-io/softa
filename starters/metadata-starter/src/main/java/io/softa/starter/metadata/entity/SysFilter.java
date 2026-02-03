package io.softa.starter.metadata.entity;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.entity.AuditableModel;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * SysFilter Model
 */
@Data
@Schema(name = "SysFilter")
@EqualsAndHashCode(callSuper = true)
public class SysFilter extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "Filter Name")
    private String name;

    @Schema(description = "Filter Conditions")
    private Filters filters;

    @Schema(description = "Model Name")
    private String model;

    @Schema(description = "Query Text")
    private String query;
}