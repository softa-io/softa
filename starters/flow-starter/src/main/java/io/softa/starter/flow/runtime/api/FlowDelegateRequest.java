package io.softa.starter.flow.runtime.api;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Request for delegating a pending approval task to another actor.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(name = "FlowDelegateRequest")
public class FlowDelegateRequest extends AbstractFlowNodeTargetActorRequest {
}

