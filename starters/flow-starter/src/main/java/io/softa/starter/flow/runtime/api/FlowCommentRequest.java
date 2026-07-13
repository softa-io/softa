package io.softa.starter.flow.runtime.api;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Request to add a comment to a flow instance without changing its status.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(name = "FlowCommentRequest")
public class FlowCommentRequest extends AbstractFlowInstanceRequest {

    @Schema(description = "Optional node id this comment relates to")
    private String nodeId;
}

