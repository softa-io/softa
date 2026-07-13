package io.softa.starter.flow.service.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.flow.entity.FlowExecutionTrace;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionTraceEntry;
import io.softa.starter.flow.runtime.state.FlowTraceEventType;
import io.softa.starter.flow.service.FlowExecutionTraceService;

/**
 * ORM-backed trace service. Keeps {@code flow_execution_trace} as the
 * source of truth and {@link FlowExecutionState#getTrace()} as an in-memory
 * delta buffer of the current attempt.
 */
@Service
public class FlowExecutionTraceServiceImpl
        extends EntityServiceImpl<FlowExecutionTrace, Long>
        implements FlowExecutionTraceService {

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void appendNewEntries(FlowExecutionState state) {
        if (state == null || state.getInstanceId() == null) {
            return;
        }
        List<FlowExecutionTraceEntry> entries = state.getTrace();
        if (entries == null || entries.isEmpty()) {
            return;
        }
        int already = Math.max(0, state.getPersistedTraceCount());
        if (already >= entries.size()) {
            return;
        }
        // The in-memory trace is a delta buffer: loaded states start it empty, so persisted
        // sequences continue from the instance's existing row count (resolved once, lazily).
        int base = state.getTraceSequenceBase();
        if (base < 0) {
            base = (int) countByInstanceId(state.getInstanceId());
            state.setTraceSequenceBase(base);
        }
        List<FlowExecutionTrace> rows = new ArrayList<>(entries.size() - already);
        for (int i = already; i < entries.size(); i++) {
            FlowExecutionTraceEntry entry = entries.get(i);
            FlowExecutionTrace row = new FlowExecutionTrace();
            row.setInstanceId(state.getInstanceId());
            row.setSequence(base + i);
            row.setFlowCode(entry.getFlowCode());
            row.setNodeId(entry.getNodeId());
            row.setFlowNodeType(entry.getFlowNodeType());
            row.setEventType(entry.getEventType());
            row.setEventTime(entry.getEventTime());
            row.setMessage(entry.getMessage());
            rows.add(row);
        }
        if (!rows.isEmpty()) {
            this.createList(rows);
            state.setPersistedTraceCount(entries.size());
        }
    }

    @Override
    public List<FlowExecutionTrace> findByInstanceId(String instanceId) {
        Filters filters = new Filters().eq(FlowExecutionTrace::getInstanceId, instanceId);
        return this.searchList(filters).stream()
                .sorted(Comparator.comparing(FlowExecutionTrace::getSequence,
                        Comparator.nullsLast(Integer::compareTo)))
                .toList();
    }

    @Override
    public List<FlowExecutionTrace> findByInstanceIdSince(String instanceId, long sinceSequence) {
        Filters filters = new Filters()
                .eq(FlowExecutionTrace::getInstanceId, instanceId)
                .gt(FlowExecutionTrace::getSequence, (int) sinceSequence);
        return this.searchList(filters).stream()
                .sorted(Comparator.comparing(FlowExecutionTrace::getSequence,
                        Comparator.nullsLast(Integer::compareTo)))
                .toList();
    }

    @Override
    public List<FlowExecutionTrace> findByInstanceIdAndEventTypes(String instanceId,
                                                                  Collection<FlowTraceEventType> eventTypes) {
        Filters filters = new Filters()
                .eq(FlowExecutionTrace::getInstanceId, instanceId)
                .in(FlowExecutionTrace::getEventType, eventTypes);
        return this.searchList(filters).stream()
                .sorted(Comparator.comparing(FlowExecutionTrace::getSequence,
                        Comparator.nullsLast(Integer::compareTo)))
                .toList();
    }

    @Override
    public long countByInstanceId(String instanceId) {
        return this.count(new Filters().eq(FlowExecutionTrace::getInstanceId, instanceId));
    }
}
