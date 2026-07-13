package io.softa.starter.flow.runtime.store;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import io.softa.starter.flow.entity.FlowExecutionTrace;
import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.runtime.state.FlowExecutionTraceEntry;
import io.softa.starter.flow.service.FlowExecutionTraceService;
import io.softa.starter.flow.service.FlowInstanceService;

/**
 * ORM-backed {@link FlowInstanceStore} that persists execution state to the database
 * via {@link FlowInstanceService}.
 *
 * <p>Trace entries are persisted separately to {@code flow_execution_trace}
 * via {@link FlowExecutionTraceService} so that long-running flows do not
 * rewrite the entire trace JSON on every save.</p>
 */
@Primary
@Component
public class OrmFlowInstanceStore implements FlowInstanceStore {

    private final FlowInstanceService instanceService;
    private final FlowExecutionTraceService traceService;
    private final ApprovalActionLedger approvalLedger;

    public OrmFlowInstanceStore(FlowInstanceService instanceService,
                                FlowExecutionTraceService traceService,
                                ApprovalActionLedger approvalLedger) {
        this.instanceService = instanceService;
        this.traceService = traceService;
        this.approvalLedger = approvalLedger;
    }

    @Override
    public FlowExecutionState save(FlowExecutionState state) {
        FlowInstance existing = instanceService.findByInstanceId(state.getInstanceId()).orElse(null);
        FlowInstance entity = FlowExecutionStateMapper.toEntity(state, existing);
        FlowInstance saved = instanceService.saveInstance(entity);
        state.setVersion(saved.getVersion());
        traceService.appendNewEntries(state);
        approvalLedger.appendNewEntries(state);
        return state;
    }

    @Override
    public Optional<FlowExecutionState> get(String instanceId) {
        // Trace intentionally NOT hydrated: loads must not scale with history length.
        // The state's empty trace is a delta buffer; the mapper marks the sequence
        // base as unresolved so a later flush continues numbering correctly.
        return instanceService.findByInstanceId(instanceId)
                .map(FlowExecutionStateMapper::toState);
    }

    @Override
    public Optional<FlowExecutionState> getWithTrace(String instanceId) {
        return instanceService.findByInstanceId(instanceId)
                .map(entity -> {
                    FlowExecutionState state = FlowExecutionStateMapper.toState(entity);
                    hydrateTrace(state);
                    return state;
                });
    }

    @Override
    public List<FlowExecutionState> listByFlowCode(String flowCode) {
        return instanceService.findByFlowCode(flowCode).stream()
                .map(FlowExecutionStateMapper::toState)
                .toList();
    }

    @Override
    public List<FlowExecutionState> listByStatus(FlowExecutionStatus status) {
        return instanceService.findByStatus(status).stream()
                .map(FlowExecutionStateMapper::toState)
                .toList();
    }

    private void hydrateTrace(FlowExecutionState state) {
        List<FlowExecutionTrace> rows = traceService.findByInstanceId(state.getInstanceId());
        List<FlowExecutionTraceEntry> entries = new ArrayList<>(rows.size());
        for (FlowExecutionTrace row : rows) {
            entries.add(FlowExecutionTraceEntry.builder()
                    .flowCode(row.getFlowCode())
                    .nodeId(row.getNodeId())
                    .flowNodeType(row.getFlowNodeType())
                    .eventType(row.getEventType())
                    .eventTime(row.getEventTime())
                    .message(row.getMessage())
                    .build());
        }
        // Hydrated states carry the FULL history in memory: watermark = size (nothing new
        // to flush) and base = 0 (list index == sequence), so a save still numbers correctly.
        state.setTrace(entries);
        state.setPersistedTraceCount(entries.size());
        state.setTraceSequenceBase(0);
    }
}
