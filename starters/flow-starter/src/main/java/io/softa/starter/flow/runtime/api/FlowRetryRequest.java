package io.softa.starter.flow.runtime.api;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * An administrator running the steps after an approval again, after they failed: the approval goes on from the step
 * it failed after. The {@code comment}, optional, is recorded with it. Authorized by the caller beforehand — the
 * monitoring console's permission and data scope, those of terminating.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(name = "FlowRetryRequest")
public class FlowRetryRequest extends AbstractFlowInstanceRequest {
}
