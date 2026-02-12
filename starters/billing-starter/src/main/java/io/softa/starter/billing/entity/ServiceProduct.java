package io.softa.starter.billing.entity;

import java.io.Serial;
import java.math.BigDecimal;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.entity.AuditableModel;
import io.softa.starter.billing.enums.ServiceCategory;

/**
 * ServiceProduct Model
 */
@Data
@Schema(name = "ServiceProduct")
@EqualsAndHashCode(callSuper = true)
public class ServiceProduct extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "Service Name")
    private String name;

    @Schema(description = "Service Description")
    private String description;

    @Schema(description = "Service Category")
    private ServiceCategory category;

    @Schema(description = "Price($)")
    private BigDecimal price;

    @Schema(description = "Service Duration(mins)")
    private Integer duration;

    @Schema(description = "Active")
    private Boolean active;

    @Schema(description = "Deleted")
    private Boolean deleted;
}