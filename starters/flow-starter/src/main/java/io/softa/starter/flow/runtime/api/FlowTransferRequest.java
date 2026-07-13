package io.softa.starter.flow.runtime.api;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Request for transferring a pending approval task to another actor.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(name = "FlowTransferRequest")
public class FlowTransferRequest extends AbstractFlowNodeTargetActorRequest {
}

