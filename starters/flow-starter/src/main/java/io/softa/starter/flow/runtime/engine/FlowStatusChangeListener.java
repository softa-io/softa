package io.softa.starter.flow.runtime.engine;

import org.jspecify.annotations.Nullable;

import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;

/**
 * Listener invoked after a save that moved an instance's status: from the one the action found it in to the one it
 * saved. Called by {@link FlowProjectionPublisher} after the {@link FlowStateChangeListener}s, in the same transaction,
 * with permission checks off; a listener that throws rolls the action back.
 *
 * <p>A save that leaves the status as it was does not reach it: a CC or a CC read, a comment, an urge, a vote that
 * leaves its step waiting, an approval that hands the instance on to its next step. A status listener follows the
 * instance's course, not every action on it — the record write-back ({@link ApprovalOutcomeListener}) is one.
 */
@FunctionalInterface
public interface FlowStatusChangeListener {

    /**
     * @param state the instance as saved, in its new status
     * @param from  the status the action found it in; {@code null} for an instance saved for the first time
     */
    void onStatusChanged(FlowExecutionState state, @Nullable FlowExecutionStatus from);
}
