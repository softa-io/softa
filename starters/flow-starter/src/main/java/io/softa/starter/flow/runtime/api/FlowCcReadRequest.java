package io.softa.starter.flow.runtime.api;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Request for acknowledging a CC task as read.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(name = "FlowCcReadRequest")
public class FlowCcReadRequest extends AbstractFlowNodeRequest {
}
