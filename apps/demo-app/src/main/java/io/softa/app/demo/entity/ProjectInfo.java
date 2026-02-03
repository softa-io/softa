package io.softa.app.demo.entity;

import io.softa.framework.orm.entity.AuditableModel;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.util.List;

/**
 * ProjectInfo Model
 */
@Data
@Schema(name = "ProjectInfo")
@EqualsAndHashCode(callSuper = true)
public class ProjectInfo extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "Name")
    private String name;

    @Schema(description = "Code")
    private String code;

    @Schema(description = "Employees")
    private List<Long> empIds;

    @Schema(description = "Description")
    private String description;

    @Schema(description = "Active")
    private Boolean active;
}