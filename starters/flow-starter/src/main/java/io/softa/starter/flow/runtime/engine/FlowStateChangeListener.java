package io.softa.starter.flow.runtime.engine;

import io.softa.starter.flow.runtime.state.FlowExecutionState;

/**
 * Listener invoked after flow execution state has been persisted.
 * Implementations typically synchronize projection tables from the state snapshot.
 */
@FunctionalInterface
public interface FlowStateChangeListener {

    void onStateChanged(FlowExecutionState state);
}

