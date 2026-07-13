package io.softa.starter.flow.runtime;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.runtime.store.FlowInstanceStore;

/**
 * Lightweight test-only instance store backed by a ConcurrentHashMap.
 */
public class StubFlowInstanceStore implements FlowInstanceStore {

    private final Map<String, FlowExecutionState> states = new ConcurrentHashMap<>();

    @Override
    public FlowExecutionState save(FlowExecutionState state) {
        states.put(state.getInstanceId(), state);
        return state;
    }

    @Override
    public Optional<FlowExecutionState> get(String instanceId) {
        return Optional.ofNullable(states.get(instanceId));
    }

    @Override
    public List<FlowExecutionState> listByFlowCode(String flowCode) {
        return states.values().stream()
                .filter(s -> flowCode != null && flowCode.equals(s.getFlowCode()))
                .toList();
    }

    @Override
    public List<FlowExecutionState> listByStatus(FlowExecutionStatus status) {
        return states.values().stream()
                .filter(s -> status != null && status.equals(s.getStatus()))
                .toList();
    }
}
