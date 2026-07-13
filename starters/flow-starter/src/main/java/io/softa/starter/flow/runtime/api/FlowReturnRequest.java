package io.softa.starter.flow.runtime.api;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Request for returning a pending approval.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(name = "FlowReturnRequest")
public class FlowReturnRequest extends AbstractFlowNodeRequest {
}

