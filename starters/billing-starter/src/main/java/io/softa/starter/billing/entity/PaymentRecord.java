package io.softa.starter.billing.entity;

import io.softa.starter.billing.enums.PaymentMethod;
import io.softa.starter.billing.enums.PaymentStatus;
import io.softa.framework.orm.entity.AuditableModel;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * PaymentRecord Model
 */
@Data
@Schema(name = "PaymentRecord")
@EqualsAndHashCode(callSuper = true)
public class PaymentRecord extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private String id;

    @Schema(description = "Order ID")
    private String orderId;

    @Schema(description = "Payment Method")
    private PaymentMethod paymentMethod;

    @Schema(description = "Payment Status")
    private PaymentStatus paymentStatus;

    @Schema(description = "Paid Amount")
    private BigDecimal paidAmount;

    @Schema(description = "Paid At")
    private LocalDateTime paidAt;

    @Schema(description = "Transaction ID")
    private String transactionId;

    @Schema(description = "Deleted")
    private Boolean deleted;
}