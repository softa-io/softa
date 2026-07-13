package io.softa.starter.flow.runtime.api;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Request for adding a follow-up signer after the current approver on a pending approval node.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(name = "FlowAddSignAfterRequest")
public class FlowAddSignAfterRequest extends AbstractFlowNodeTargetActorRequest {
}
