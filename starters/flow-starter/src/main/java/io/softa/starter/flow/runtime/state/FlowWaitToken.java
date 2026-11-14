package io.softa.starter.flow.runtime.state;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * An AsyncTask node a flow instance waits on for its callback. Approval waits are kept apart, as the instance's
 * pending approvals.
 * <p>
 * An instance may hold several tokens at once (parallel branches each suspended on an async task), so waits are
 * expressed as a list rather than a single {@code suspendedNodeId}. The node id is the correlation key: a node
 * executes at most once per traversal, so at most one live token exists per node.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "FlowWaitToken")
public class FlowWaitToken {

    @Schema(description = "AsyncTask node that suspended the branch")
    private String nodeId;
}
