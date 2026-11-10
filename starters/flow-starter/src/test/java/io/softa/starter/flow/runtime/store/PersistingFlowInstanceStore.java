package io.softa.starter.flow.runtime.store;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;

/**
 * A test store that keeps instances as the ORM store does — as their rows — so a load returns the instance as it
 * was saved, not the object last changed in memory. The trace and the approval audit are not kept.
 */
public class PersistingFlowInstanceStore implements FlowInstanceStore {

    private final Map<String, FlowInstance> rows = new ConcurrentHashMap<>();

    @Override
    public FlowExecutionState save(FlowExecutionState state) {
        rows.put(state.getInstanceId(), FlowExecutionStateMapper.toEntity(state, rows.get(state.getInstanceId())));
        return state;
    }

    @Override
    public Optional<FlowExecutionState> get(String instanceId) {
        return Optional.ofNullable(rows.get(instanceId)).map(FlowExecutionStateMapper::toState);
    }

    @Override
    public List<FlowExecutionState> listByStatus(FlowExecutionStatus status) {
        return rows.values().stream().filter(row -> status.equals(row.getStatus()))
                .map(FlowExecutionStateMapper::toState).toList();
    }
}
