package io.softa.starter.flow.service;

import java.util.Collection;
import java.util.List;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.flow.entity.FlowExecutionTrace;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowTraceEventType;

/**
 * Service for persisting and reading execution trace entries.
 *
 * <p>The runtime keeps {@link FlowExecutionState#getTrace()} in memory as a
 * <b>delta buffer</b> — only the entries of the current attempt. Loads leave it
 * empty (the full history is not hydrated); this service flushes new entries to
 * {@code flow_execution_trace} on every state save, continuing the sequence from
 * {@code state.traceSequenceBase}. Consumers that need history read it from the
 * table, not from the state.</p>
 */
public interface FlowExecutionTraceService extends EntityService<FlowExecutionTrace, Long> {

    /**
     * Persist any trace entries that have accumulated on {@code state.getTrace()}
     * since the last flush. Entries already persisted (tracked via
     * {@code state.getPersistedTraceCount()}) are not re-inserted; sequence numbers
     * continue from {@code state.getTraceSequenceBase()}, resolved lazily with one
     * COUNT for loaded states.
     *
     * <p>After a successful flush, {@code state.persistedTraceCount} is advanced
     * to {@code state.getTrace().size()}.</p>
     */
    void appendNewEntries(FlowExecutionState state);

    /**
     * Load all trace entries for an instance ordered by {@code sequence}.
     */
    List<FlowExecutionTrace> findByInstanceId(String instanceId);

    /**
     * Load trace entries with {@code sequence > sinceSequence}, ordered by sequence —
     * the incremental fetch a polling client uses while an instance is running.
     */
    List<FlowExecutionTrace> findByInstanceIdSince(String instanceId, long sinceSequence);

    /**
     * Load only the entries of the given event types, ordered by sequence — for
     * projections (e.g. parallel-branch fork/join) that must see the full history
     * without loading every row.
     */
    List<FlowExecutionTrace> findByInstanceIdAndEventTypes(String instanceId,
                                                           Collection<FlowTraceEventType> eventTypes);

    /**
     * Number of trace rows the instance already has — the sequence base for a
     * loaded state's next flush.
     */
    long countByInstanceId(String instanceId);
}
