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
 */
@Slf4j
final class FlowExecutionStateMapper {

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
        entity.setDesignId(state.getDesignId());
        entity.setFlowCode(state.getFlowCode());
        entity.setFlowRevision(state.getFlowRevision());
        entity.setTitle(state.getTitle());
        entity.setModelName(state.getModelName());
        entity.setRowId(state.getRowId());
        entity.setInitiatorId(state.getInitiatorId());
        entity.setStatus(state.getStatus());
        entity.setResubmissionCount(state.getResubmissionCount());
        entity.setErrorMessage(state.getErrorMessage());
        entity.setFailedNodeId(state.getFailedNodeId());
        entity.setInputPayload(JsonUtils.objectToString(state.getInputPayload()));
        entity.setVariables(JsonUtils.objectToString(state.getVariables()));
        entity.setWaitTokens(JsonUtils.objectToString(state.getWaitTokens()));
        entity.setNextFireAt(state.earliestTimerDueAt());
        entity.setCompletedNodeIds(JsonUtils.objectToString(state.getCompletedNodeIds()));
        entity.setPendingApprovals(JsonUtils.objectToString(state.getPendingApprovals()));
        entity.setReturnedApproval(JsonUtils.objectToString(state.getReturnedApproval()));
        entity.setJoinArrivalCounts(JsonUtils.objectToString(state.getJoinArrivalCounts()));
        // trace is persisted separately via flow_execution_trace
        entity.setReturnData(JsonUtils.objectToString(state.getReturnData()));
        return entity;
    }

    /**
     * Convert persistence entity to runtime state.
     */
    static FlowExecutionState toState(FlowInstance entity) {
        return FlowExecutionState.builder()
                .instanceId(entity.getInstanceId())
                .version(entity.getVersion())
                .bundleId(entity.getBundleId())
                .designId(entity.getDesignId())
                .flowCode(entity.getFlowCode())
                .flowRevision(entity.getFlowRevision())
                .title(entity.getTitle())
                .modelName(entity.getModelName())
                .rowId(entity.getRowId())
                .initiatorId(entity.getInitiatorId())
                .status(entity.getStatus())
                .resubmissionCount(entity.getResubmissionCount())
                .errorMessage(entity.getErrorMessage())
                .failedNodeId(entity.getFailedNodeId())
                .inputPayload(JsonUtils.stringToObject(entity.getInputPayload(), new TypeReference<>() {}, new LinkedHashMap<>()))
                .variables(JsonUtils.stringToObject(entity.getVariables(), new TypeReference<>() {}, new LinkedHashMap<>()))
                .waitTokens(JsonUtils.stringToObject(entity.getWaitTokens(), new TypeReference<>() {}, new ArrayList<>()))
                .completedNodeIds(JsonUtils.stringToObject(entity.getCompletedNodeIds(), new TypeReference<>() {}, new ArrayList<>()))
                .pendingApprovals(JsonUtils.stringToObject(entity.getPendingApprovals(), new TypeReference<>() {}, new ArrayList<>()))
                .returnedApproval(JsonUtils.stringToObject(entity.getReturnedApproval(), new TypeReference<>() {}, null))
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
