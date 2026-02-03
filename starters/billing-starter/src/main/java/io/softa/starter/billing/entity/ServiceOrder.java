package io.softa.starter.billing.entity;

import io.softa.starter.billing.enums.OrderStatus;
import io.softa.framework.orm.entity.AuditableModel;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.math.BigDecimal;

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
    private String id;

    @Schema(description = "User")
    private String userId;

    @Schema(description = "Service Product")
    private String serviceId;

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