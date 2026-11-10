package io.softa.starter.flow.runtime.api;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Request to urge (催办) pending approvers on a flow instance: {@code comment} is the reminder's text, a default one
 * when empty.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(name = "FlowUrgeRequest")
public class FlowUrgeRequest extends AbstractFlowInstanceRequest {
}

