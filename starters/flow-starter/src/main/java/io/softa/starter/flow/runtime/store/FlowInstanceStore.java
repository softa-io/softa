package io.softa.starter.flow.runtime.store;

import java.util.List;
import java.util.Optional;

import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;

/**
 * Store for runtime execution states.
 */
public interface FlowInstanceStore {

    FlowExecutionState save(FlowExecutionState state);

    /**
     * Load the resumable state of an instance. The trace is NOT hydrated — it stays an
     * empty delta buffer, so loads do not scale with history length. Use
     * {@link #getWithTrace} for view paths that render the event history.
     */
    Optional<FlowExecutionState> get(String instanceId);

    /**
     * {@link #get} plus the full trace history hydrated from the trace store — for
     * view/projection paths only.
     */
    default Optional<FlowExecutionState> getWithTrace(String instanceId) {
        return get(instanceId);
    }

    /**
     * List all instances matching the given flow code (trace not hydrated).
     */
    List<FlowExecutionState> listByFlowCode(String flowCode);

    /**
     * List all instances matching the given execution status (trace not hydrated).
     * Abstract on purpose: timeout reminders and timer sweeps depend on it, so a
     * store that misses it must fail to compile instead of silently sweeping nothing.
     */
    List<FlowExecutionState> listByStatus(FlowExecutionStatus status);
}

