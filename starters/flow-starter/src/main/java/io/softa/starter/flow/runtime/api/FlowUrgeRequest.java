package io.softa.starter.flow.runtime.api;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Request to urge (催办) pending approvers on a flow instance.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(name = "FlowUrgeRequest")
public class FlowUrgeRequest extends AbstractFlowInstanceRequest {

    @Schema(description = "Optional urge message")
    private String message;
}

