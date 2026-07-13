package io.softa.starter.flow.runtime.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Request for acknowledging a CC task as read.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(name = "FlowCcReadRequest")
public class FlowCcReadRequest extends AbstractFlowNodeRequest {

    @NotNull(message = "cycleNumber is required")
    @Schema(description = "Approval cycle number of the CC task")
    private Integer cycleNumber;
}
