package io.softa.starter.studio.release.entity;

import java.io.Serial;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.entity.AuditableModel;
import io.softa.starter.studio.release.enums.DesignPortfolioStatus;

/**
 * DesignPortfolio Model
 */
@Data
@Schema(name = "DesignPortfolio")
@EqualsAndHashCode(callSuper = true)
public class DesignPortfolio extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "Owner")
    private Long ownerId;

    @Schema(description = "Name")
    private String name;

    @Schema(description = "Code")
    private String code;

    @Schema(description = "Status")
    private DesignPortfolioStatus status;

    @Schema(description = "Description")
    private String description;

    @Schema(description = "Deleted")
    private Boolean deleted;
}
