package io.softa.starter.flow.runtime.api;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Request for sending a CC recipient on a pending approval node.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(name = "FlowCcRequest")
public class FlowCcRequest extends AbstractFlowNodeTargetActorRequest {
}
