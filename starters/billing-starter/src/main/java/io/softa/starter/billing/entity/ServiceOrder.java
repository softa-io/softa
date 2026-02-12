package io.softa.starter.billing.entity;

import java.io.Serial;
import java.math.BigDecimal;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.entity.AuditableModel;
import io.softa.starter.billing.enums.OrderStatus;

/**
 * ServiceOrder Model
 */
@Data
@Schema(name = "ServiceOrder")
@EqualsAndHashCode(callSuper = true)
public class ServiceOrder extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "User")
    private Long userId;

    @Schema(description = "Service Product")
    private Long serviceId;

    @Schema(description = "Order Number")
    private String orderNumber;

    @Schema(description = "Order Status")
    private OrderStatus orderStatus;

    @Schema(description = "Amount")
    private BigDecimal amount;

    @Schema(description = "Notes")
    private String notes;

    @Schema(description = "Deleted")
    private Boolean deleted;
}