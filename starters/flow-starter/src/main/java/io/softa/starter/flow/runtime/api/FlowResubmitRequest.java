package io.softa.starter.flow.runtime.api;

import java.util.Map;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Request for resubmitting a previously returned approval instance.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(name = "FlowResubmitRequest")
public class FlowResubmitRequest extends AbstractFlowInstanceRequest {

    @Schema(description = "Optional variable updates merged before the approval is recreated")
    private Map<String, Object> variables;
}

