package io.softa.framework.orm.entity;

import java.io.Serial;
import java.time.LocalDateTime;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Abstract class of base model, with id and audit fields.
 */
@Data
@Schema(name = "AuditableModel")
@EqualsAndHashCode(callSuper = true)
public abstract class AuditableModel extends AbstractModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "Creation time")
    protected LocalDateTime createdTime;

    @Schema(description = "Creator ID")
    protected String createdId;

    @Schema(description = "Created By")
    protected String createdBy;

    @Schema(description = "Update time")
    protected LocalDateTime updatedTime;

    @Schema(description = "Updater ID")
    protected String updatedId;

    @Schema(description = "Updated By")
    protected String updatedBy;

}
