package io.softa.starter.flow.runtime.store;

import java.util.Collection;
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
     * The states of these instances, those that exist, in no particular order (trace not hydrated) — for
     * a list page that shows what can be done to each of its rows. One read where the store can batch it.
     */
    default List<FlowExecutionState> listByInstanceIds(Collection<String> instanceIds) {
        return instanceIds.stream().map(this::get).flatMap(Optional::stream).toList();
    }

    /**
     * List all instances matching the given execution status (trace not hydrated).
     * Abstract on purpose: timeout reminders and timer sweeps depend on it, so a
     * store that misses it must fail to compile instead of silently sweeping nothing.
     */
    List<FlowExecutionState> listByStatus(FlowExecutionStatus status);
}

