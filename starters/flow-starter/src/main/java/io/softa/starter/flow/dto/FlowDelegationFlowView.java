package io.softa.starter.flow.dto;

import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * An approval flow a delegation rule can be scoped to — scope {@code Flow}, or {@code Node} with one of its approval
 * steps: an enabled Approval flow of the caller's tenant, with the approval steps of its effective revision.
 */
@Schema(name = "FlowDelegationFlowView")
public record FlowDelegationFlowView(

        @Schema(description = "Flow id: the delegation's flowId")
        String flowId,

        @Schema(description = "Flow name")
        String flowName,

        @Schema(description = "The approval steps of the flow's effective revision, in flow order")
        List<Node> nodes
) {

    /** An approval step a delegation rule of scope {@code Node} can name. */
    @Schema(name = "FlowDelegationFlowNode")
    public record Node(

            @Schema(description = "Node id: the delegation's nodeId")
            String nodeId,

            @Schema(description = "The step's label")
            String label
    ) {
    }
}
