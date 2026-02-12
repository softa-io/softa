package io.softa.starter.billing.entity;

import java.io.Serial;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import tools.jackson.databind.JsonNode;

import io.softa.framework.orm.entity.AuditableModel;

/**
 * ServiceRecord Model
 */
@Data
@Schema(name = "ServiceRecord")
@EqualsAndHashCode(callSuper = true)
public class ServiceRecord extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "User")
    private Long userId;

    @Schema(description = "Service Product")
    private Long serviceId;

    @Schema(description = "Order ID")
    private Long orderId;

    @Schema(description = "Request Data")
    private JsonNode requestData;

    @Schema(description = "Result Summary")
    private String resultSummary;

    @Schema(description = "Result Detail")
    private String resultDetail;

    @Schema(description = "Deleted")
    private Boolean deleted;
}