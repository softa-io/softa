package io.softa.starter.flow.runtime.engine;

import io.softa.starter.flow.runtime.state.FlowExecutionState;

/**
 * Listener invoked after every save of a flow execution state, whatever the action changed: implementations keep
 * projections that follow each action — the approval tasks, the inbox notices marked read. A listener that follows
 * only the instance's status moving is a {@link FlowStatusChangeListener}.
 */
@FunctionalInterface
public interface FlowStateChangeListener {

    void onStateChanged(FlowExecutionState state);
}
