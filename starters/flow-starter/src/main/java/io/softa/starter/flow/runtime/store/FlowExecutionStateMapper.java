package io.softa.starter.flow.runtime.store;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.type.TypeReference;

import io.softa.framework.base.utils.JsonUtils;
import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.runtime.state.FlowExecutionState;

/**
 * Bidirectional mapper between {@link FlowExecutionState} (runtime) and
 * {@link FlowInstance} (persistence entity).
 * Complex nested objects are serialized as JSON strings for DB storage.
 *
 * <p>{@link #toState} is public for the reads that already hold the rows: a record's approvals come
 * as states from the one query that finds them ({@code FlowInstanceService.recordApprovalStates}).
 * Writing stays with the store.</p>
 */
@Slf4j
public final class FlowExecutionStateMapper {

    private FlowExecutionStateMapper() {
    }

    /**
     * Convert runtime state to persistence entity.
     */
    static FlowInstance toEntity(FlowExecutionState state, FlowInstance existing) {
        FlowInstance entity = existing != null ? existing : new FlowInstance();
        entity.setInstanceId(state.getInstanceId());
        entity.setVersion(state.getVersion());
        entity.setBundleId(state.getBundleId());
        entity.setFlowId(state.getFlowId());
        entity.setFlowRevision(state.getFlowRevision());
        entity.setTitle(state.getTitle());
        entity.setModelName(state.getModelName());
        entity.setRowId(state.getRowId());
        entity.setInitiatorId(state.getInitiatorId());
        entity.setRecordApproval(state.getRecordApproval());
        entity.setSubjectId(state.getSubjectId());
        entity.setDepartmentId(state.getDepartmentId());
        entity.setEffectiveDate(state.getEffectiveDate());
        entity.setRecordCode(state.getRecordCode());
        entity.setTriggerEventId(state.getTriggerEventId());
        // Held while the record's approval is unfinished; the unique index on it keeps a record
        // to one approval in progress, concurrent submissions included.
        boolean open = state.isApprovalOfRecord() && state.getStatus() != null && !state.getStatus().isTerminal();
        entity.setOpenRecordId(open ? state.getRowId() : null);
        entity.setStatus(state.getStatus());
        entity.setErrorMessage(state.getErrorMessage());
        entity.setFailedNodeId(state.getFailedNodeId());
        entity.setInputPayload(JsonUtils.objectToString(state.getInputPayload()));
        entity.setVariables(JsonUtils.objectToString(state.getVariables()));
        entity.setWaitTokens(JsonUtils.objectToString(state.getWaitTokens()));
        entity.setNextFireAt(state.earliestTimerDueAt());
        entity.setCompletedNodeIds(JsonUtils.objectToString(state.getCompletedNodeIds()));
        entity.setPendingApprovals(JsonUtils.objectToString(state.getPendingApprovals()));
        entity.setJoinArrivalCounts(JsonUtils.objectToString(state.getJoinArrivalCounts()));
        // trace is persisted separately via flow_execution_trace
        entity.setReturnData(JsonUtils.objectToString(state.getReturnData()));
        return entity;
    }

    /**
     * Convert persistence entity to runtime state — the state {@link FlowInstanceStore#get} loads: the trace and
     * the approval audit stay empty delta buffers.
     */
    public static FlowExecutionState toState(FlowInstance entity) {
        return FlowExecutionState.builder()
                .instanceId(entity.getInstanceId())
                .version(entity.getVersion())
                .bundleId(entity.getBundleId())
                .flowId(entity.getFlowId())
                .flowName(entity.getFlowName())
                .flowRevision(entity.getFlowRevision())
                .title(entity.getTitle())
                .modelName(entity.getModelName())
                .rowId(entity.getRowId())
                .initiatorId(entity.getInitiatorId())
                .recordApproval(entity.getRecordApproval())
                .subjectId(entity.getSubjectId())
                .departmentId(entity.getDepartmentId())
                .effectiveDate(entity.getEffectiveDate())
                .recordCode(entity.getRecordCode())
                .triggerEventId(entity.getTriggerEventId())
                .status(entity.getStatus())
                .errorMessage(entity.getErrorMessage())
                .failedNodeId(entity.getFailedNodeId())
                .inputPayload(JsonUtils.stringToObject(entity.getInputPayload(), new TypeReference<>() {}, new LinkedHashMap<>()))
                .variables(JsonUtils.stringToObject(entity.getVariables(), new TypeReference<>() {}, new LinkedHashMap<>()))
                .waitTokens(JsonUtils.stringToObject(entity.getWaitTokens(), new TypeReference<>() {}, new ArrayList<>()))
                .completedNodeIds(JsonUtils.stringToObject(entity.getCompletedNodeIds(), new TypeReference<>() {}, new ArrayList<>()))
                .pendingApprovals(JsonUtils.stringToObject(entity.getPendingApprovals(), new TypeReference<>() {}, new ArrayList<>()))
                // approval audit is a delta buffer: loads keep it empty; history lives in
                // flow_approval_record. -1 = sequence base unknown, resolved lazily on first flush.
                .approvalAuditDelta(new ArrayList<>())
                .auditSequenceBase(-1)
                .joinArrivalCounts(JsonUtils.stringToObject(entity.getJoinArrivalCounts(), new TypeReference<>() {}, new LinkedHashMap<>()))
                // trace is a delta buffer: loads keep it empty; history lives in flow_execution_trace.
                // -1 = sequence base unknown, resolved lazily (one COUNT) on the first flush.
                .trace(new ArrayList<>())
                .traceSequenceBase(-1)
                .returnData(JsonUtils.stringToObject(entity.getReturnData(), new TypeReference<>() {}, null))
                .build();
    }
}
