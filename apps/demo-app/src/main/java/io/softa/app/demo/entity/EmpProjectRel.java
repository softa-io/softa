package io.softa.app.demo.entity;

import java.io.Serial;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.entity.AuditableModel;

/**
 * EmpProjectRel Model
 */
@Data
@Schema(name = "EmpProjectRel")
@EqualsAndHashCode(callSuper = true)
public class EmpProjectRel extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "Employee ID")
    private Long empId;

    @Schema(description = "Project ID")
    private Long projectId;

    @Schema(description = "Tenant ID")
    private Long tenantId;
}