package io.softa.starter.billing.entity;

import tools.jackson.databind.JsonNode;
import io.softa.framework.orm.entity.AuditableModel;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

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
    private String id;

    @Schema(description = "User")
    private String userId;

    @Schema(description = "Service Product")
    private String serviceId;

    @Schema(description = "Order ID")
    private String orderId;

    @Schema(description = "Request Data")
    private JsonNode requestData;

    @Schema(description = "Result Summary")
    private String resultSummary;

    @Schema(description = "Result Detail")
    private String resultDetail;

    @Schema(description = "Deleted")
    private Boolean deleted;
}