package io.softa.starter.flow.runtime.state;

import java.time.LocalDateTime;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One reason a flow instance is parked, pinned to the node that produced it.
 * <p>
 * An instance may hold several tokens at once (parallel branches each suspended
 * on a timer / async node), so waits are expressed as a list rather than a single
 * {@code suspendedNodeId}. The node id is the correlation key: a node executes at
 * most once per traversal, so at most one live token exists per node.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "FlowWaitToken")
public class FlowWaitToken {

    @Schema(description = "Node that suspended the branch")
    private String nodeId;

    @Schema(description = "Why the branch is waiting")
    private WaitType type;

    @Schema(description = "When a TIMER wait is due to fire; null for ASYNC waits")
    private LocalDateTime dueAt;
}
