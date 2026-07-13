package io.softa.starter.flow.runtime.api;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Request for adding a prerequisite signer before the current approver on a pending approval node.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(name = "FlowAddSignBeforeRequest")
public class FlowAddSignBeforeRequest extends AbstractFlowNodeTargetActorRequest {
}

