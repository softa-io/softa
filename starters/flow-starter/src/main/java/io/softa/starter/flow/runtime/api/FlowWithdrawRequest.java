package io.softa.starter.flow.runtime.api;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Request for initiator withdrawal of an in-progress approval flow.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(name = "FlowWithdrawRequest")
public class FlowWithdrawRequest extends AbstractFlowInstanceRequest {
}

