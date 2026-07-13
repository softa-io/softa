package io.softa.starter.flow.runtime.api;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Request for sending CC notifications to multiple recipients on one pending approval node.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(name = "FlowBatchCcRequest")
public class FlowBatchCcRequest extends AbstractFlowNodeBatchTargetActorsRequest {
}
