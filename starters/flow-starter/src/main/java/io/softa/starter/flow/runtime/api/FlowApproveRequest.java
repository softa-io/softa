package io.softa.starter.flow.runtime.api;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Request for approving a pending approval node.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(name = "FlowApproveRequest")
public class FlowApproveRequest extends AbstractFlowNodeRequest {
}

