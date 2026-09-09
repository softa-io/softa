package io.softa.starter.flow.service.support;

import java.time.LocalDateTime;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.SubQueries;
import io.softa.framework.orm.domain.SubQuery;
import io.softa.framework.orm.enums.ConvertType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.flow.entity.FlowApprovalTask;
import io.softa.starter.flow.enums.FlowApprovalTaskStatus;
import io.softa.starter.flow.enums.FlowApprovalTaskType;
import io.softa.starter.flow.enums.FormFieldPermission;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.FlowBundleRegistry;
import io.softa.starter.flow.runtime.engine.ApprovalAuditReader;
import io.softa.starter.flow.runtime.engine.FormPermissionService;
import io.softa.starter.flow.runtime.state.ApprovalActionAuditEntry;
import io.softa.starter.flow.runtime.state.ApprovalActionType;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.PendingApproval;

/**
 * Builds per-actor approval task projections from runtime pending approvals.
 *
 * <p><b>Form snapshot.</b> When the node declares {@code formPermissions}, each projected task
 * carries a JSON snapshot of the bound business row's non-hidden configured fields, taken from the
 * row as it stands when the task first materializes. The snapshot is what an approver's inbox can
 * render without any read grant on the business model — the approver of an expense claim is
 * neither its creator nor in any row scope over it, so the task row itself has to carry what the
 * step is about. Write-once semantics live in {@code FlowApprovalTaskServiceImpl.syncFromState}:
 * re-projections never overwrite a stored snapshot, so later edits to the business row do not
 * rewrite what earlier approvers saw. The row read runs inside the engine's already-exempt
 * write-back ({@code onStateChanged}), so row scope does not blank it.</p>
 */
@Slf4j
@Component
public class FlowApprovalTaskProjector {

    /** Sub-table rows per one-to-many snapshot field — bounds LONGTEXT growth on wide children. */
    private static final int MAX_SNAPSHOT_SUB_ROWS = 200;

    private final ApprovalAuditReader auditReader;
    private final FlowBundleRegistry bundleRegistry;
    private final ModelService<?> modelService;

    public FlowApprovalTaskProjector(ApprovalAuditReader auditReader,
                                     FlowBundleRegistry bundleRegistry,
                                     ModelService<?> modelService) {
        this.auditReader = auditReader;
        this.bundleRegistry = bundleRegistry;
        this.modelService = modelService;
    }

    public List<FlowApprovalTask> project(FlowExecutionState state) {
        if (state == null) {
            return List.of();
        }
        // Fetched once per projection: ledger rows + the attempt's unflushed tail.
        List<ApprovalActionAuditEntry> history = auditReader.fullHistory(state);
        FormSnapshotSource snapshots = new FormSnapshotSource(state);
        List<FlowApprovalTask> result = new ArrayList<>();
        if (state.getPendingApprovals() != null) {
            for (PendingApproval pendingApproval : state.getPendingApprovals()) {
                Set<String> approvers = new LinkedHashSet<>(pendingApproval.getApprovers() == null ? List.of() : pendingApproval.getApprovers());
                for (String actorId : approvers) {
                    ApprovalActionAuditEntry latestAudit = findLatestAudit(
                            history,
                            pendingApproval.getNodeId(),
                            pendingApproval.getCycleNumber(),
                            actorId);
                    FlowApprovalTaskStatus status = resolveStatus(pendingApproval, actorId);
                    FlowApprovalTask task = buildTask(state, pendingApproval, actorId, status, latestAudit);
                    task.setFormSnapshot(snapshots.forNode(pendingApproval.getNodeId()));
                    result.add(task);
                }
            }
        }
        for (FlowApprovalTask ccTask : buildCcTasks(state, history)) {
            ccTask.setFormSnapshot(snapshots.forNode(ccTask.getNodeId()));
            result.add(ccTask);
        }
        return result;
    }

    /**
     * Per-projection snapshot builder: resolves the compiled definition once, loads the business
     * row at most once (only when some node actually declares form fields), and caches the
     * rendered JSON per node id. Anything missing — no bundle, no config, no bound row — yields
     * {@code null}, which leaves the column empty exactly as before.
     */
    private final class FormSnapshotSource {
        private final FlowExecutionState state;
        private final Map<String, String> byNodeId = new HashMap<>();
        private CompiledFlowDefinition definition;
        private boolean definitionResolved;
        private Map<String, Object> row;
        private boolean rowResolved;

        private FormSnapshotSource(FlowExecutionState state) {
            this.state = state;
        }

        private String forNode(String nodeId) {
            if (nodeId == null) {
                return null;
            }
            return byNodeId.computeIfAbsent(nodeId, this::build);
        }

        private String build(String nodeId) {
            CompiledFlowDefinition def = definition();
            if (def == null || def.getNodeIndex() == null) {
                return null;
            }
            Map<String, FormFieldPermission> permissions =
                    FormPermissionService.getFieldPermissions(def.getNodeIndex().get(nodeId));
            if (permissions.isEmpty()) {
                return null;
            }
            Map<String, Object> values = businessRow();
            if (values == null) {
                return null;
            }
            // Keep the design's field order so the rendered form reads as authored.
            Map<String, Object> snapshot = new LinkedHashMap<>();
            for (Map.Entry<String, FormFieldPermission> entry : permissions.entrySet()) {
                if (FormFieldPermission.HIDDEN.equals(entry.getValue())) {
                    continue;
                }
                snapshot.put(entry.getKey(), values.get(entry.getKey()));
            }
            return snapshot.isEmpty() ? null : JsonUtils.objectToString(snapshot);
        }

        private CompiledFlowDefinition definition() {
            if (!definitionResolved) {
                definitionResolved = true;
                definition = state.getBundleId() == null
                        ? null
                        : bundleRegistry.getByBundleId(state.getBundleId()).orElse(null);
            }
            return definition;
        }

        private Map<String, Object> businessRow() {
            if (!rowResolved) {
                rowResolved = true;
                if (state.getModelName() != null && state.getRowId() != null) {
                    row = loadBusinessRow();
                }
            }
            return row;
        }

        /**
         * One read serves every node: expands each ONE_TO_MANY field any node's form declares
         * (detail lines belong in the review form as much as header fields do, and the plain read
         * would silently omit them), and resolves references to display names so the snapshot is
         * renderable without further lookups the approver may not be entitled to make.
         */
        private Map<String, Object> loadBusinessRow() {
            String modelName = state.getModelName();
            // Filter on ID rather than getById: rowId is a string and this stays
            // agnostic of the model's id type, same as ApprovalFormWriteService.
            FlexQuery query = new FlexQuery(new Filters().eq(ModelConstant.ID, state.getRowId()));
            query.setConvertType(ConvertType.REFERENCE);
            List<String> subTableFields = new ArrayList<>();
            for (String field : configuredFormFields()) {
                if (isOneToMany(modelName, field)) {
                    subTableFields.add(field);
                }
            }
            if (!subTableFields.isEmpty()) {
                SubQueries subQueries = new SubQueries();
                subTableFields.forEach(field -> subQueries.expand(field, new SubQuery()));
                query.setSubQueries(subQueries);
            }
            Map<String, Object> loaded = modelService.searchOne(modelName, query).orElse(null);
            if (loaded != null) {
                for (String field : subTableFields) {
                    loaded.put(field, sanitizeSubRows(modelName, field, loaded.get(field)));
                }
            }
            return loaded;
        }

        /** Union of every approval node's declared form fields — the row is loaded once for all. */
        private Set<String> configuredFormFields() {
            CompiledFlowDefinition def = definition();
            if (def == null || def.getNodeIndex() == null) {
                return Set.of();
            }
            Set<String> fields = new LinkedHashSet<>();
            def.getNodeIndex().values().forEach(node ->
                    fields.addAll(FormPermissionService.getFieldPermissions(node).keySet()));
            return fields;
        }

        private boolean isOneToMany(String modelName, String field) {
            // existModel first: existField THROWS on an unregistered model, and a snapshot is
            // decoration — a stale/foreign model name must degrade, never break task projection.
            if (!ModelManager.existModel(modelName) || !ModelManager.existField(modelName, field)) {
                return false;
            }
            return FieldType.ONE_TO_MANY.equals(ModelManager.getModelField(modelName, field).getFieldType());
        }

        /**
         * Snapshot hygiene for sub-table rows: cap the row count (the column is a LONGTEXT, not a
         * table), and strip bookkeeping columns — audit stamps, tenant id, soft-delete flag,
         * version and the back-reference to the parent — which say nothing about what is being
         * approved.
         */
        private Object sanitizeSubRows(String modelName, String field, Object value) {
            if (!(value instanceof List<?> rows)) {
                return value;
            }
            MetaField metaField = ModelManager.getModelField(modelName, field);
            String backRef = metaField == null ? null : metaField.getRelatedField();
            List<?> bounded = rows;
            if (rows.size() > MAX_SNAPSHOT_SUB_ROWS) {
                log.warn("Form snapshot for {}.{} truncated to {} of {} sub-rows",
                        modelName, field, MAX_SNAPSHOT_SUB_ROWS, rows.size());
                bounded = rows.subList(0, MAX_SNAPSHOT_SUB_ROWS);
            }
            List<Object> sanitized = new ArrayList<>(bounded.size());
            for (Object rowObj : bounded) {
                if (rowObj instanceof Map<?, ?> subRow) {
                    Map<String, Object> kept = new LinkedHashMap<>();
                    subRow.forEach((k, v) -> {
                        String key = String.valueOf(k);
                        if (!isBookkeepingField(key, backRef)) {
                            kept.put(key, v);
                        }
                    });
                    sanitized.add(kept);
                } else {
                    sanitized.add(rowObj);
                }
            }
            return sanitized;
        }

        private boolean isBookkeepingField(String field, String backRef) {
            return ModelConstant.AUDIT_FIELDS.contains(field)
                    || ModelConstant.TENANT_ID.equals(field)
                    || ModelConstant.SOFT_DELETED_FIELD.equals(field)
                    || ModelConstant.VERSION.equals(field)
                    || field.equals(backRef);
        }
    }

    private List<FlowApprovalTask> buildCcTasks(FlowExecutionState state, List<ApprovalActionAuditEntry> history) {
        if (history.isEmpty()) {
            return List.of();
        }
        List<FlowApprovalTask> result = new ArrayList<>();
        for (ApprovalActionAuditEntry entry : history) {
            if (!ApprovalActionType.CC.equals(entry.getAction()) || entry.getTargetActorId() == null) {
                continue;
            }
            FlowApprovalTask task = new FlowApprovalTask();
            task.setInstanceId(state.getInstanceId());
            task.setFlowCode(entry.getFlowCode() == null ? state.getFlowCode() : entry.getFlowCode());
            task.setFlowRevision(entry.getFlowRevision() == null ? state.getFlowRevision() : entry.getFlowRevision());
            task.setNodeId(entry.getNodeId());
            task.setNodeLabel(entry.getNodeLabel());
            task.setCycleNumber(entry.getCycleNumber());
            task.setActorId(entry.getTargetActorId());
            task.setStatus(FlowApprovalTaskStatus.PENDING);
            task.setTaskType(FlowApprovalTaskType.CC);
            task.setAction(entry.getAction());
            task.setComment(entry.getComment());
            task.setDynamicApprovers(Boolean.TRUE.equals(entry.getDynamicApprovers()));
            task.setApprovalMode(entry.getApprovalMode());
            task.setRequiredApprovalCount(entry.getRequiredApprovalCount());
            task.setTotalApproverCount(entry.getTotalApproverCount());
            task.setRejectMode(entry.getRejectMode());
            task.setRequiredRejectCount(entry.getRequiredRejectCount());
            task.setCandidateActors(List.of(entry.getTargetActorId()));
            task.setApprovedActors(entry.getApprovedActors() == null ? List.of() : List.copyOf(entry.getApprovedActors()));
            task.setRejectedActors(entry.getRejectedActors() == null ? List.of() : List.copyOf(entry.getRejectedActors()));
            task.setBlocked(Boolean.FALSE);
            task.setBlockedByActorId(null);
            task.setStartTime(resolveEventTime(entry));
            task.setEndTime(resolveEventTime(entry));
            task.setClosedByActorId(entry.getActorId());
            result.add(task);
        }
        return result;
    }


    private FlowApprovalTask buildTask(FlowExecutionState state,
                                              PendingApproval pendingApproval,
                                              String actorId,
                                              FlowApprovalTaskStatus status,
                                              ApprovalActionAuditEntry latestAudit) {
        FlowApprovalTask task = new FlowApprovalTask();
        task.setInstanceId(state.getInstanceId());
        task.setFlowCode(pendingApproval.getFlowCode());
        task.setFlowRevision(pendingApproval.getFlowRevision());
        task.setNodeId(pendingApproval.getNodeId());
        task.setNodeLabel(pendingApproval.getNodeLabel());
        task.setCycleNumber(pendingApproval.getCycleNumber());
        task.setActorId(actorId);
        task.setStatus(status);
        task.setTaskType(FlowApprovalTaskType.APPROVAL);
        task.setDynamicApprovers(Boolean.TRUE.equals(pendingApproval.getDynamicApprovers()));
        task.setApprovalMode(pendingApproval.getApprovalMode());
        task.setRequiredApprovalCount(pendingApproval.getRequiredApprovalCount());
        task.setTotalApproverCount(pendingApproval.getTotalApproverCount());
        task.setRejectMode(pendingApproval.getRejectMode());
        task.setRequiredRejectCount(pendingApproval.getRequiredRejectCount());
        task.setCandidateActors(List.copyOf(new LinkedHashSet<>(pendingApproval.getApprovers() == null ? List.of() : pendingApproval.getApprovers())));
        task.setApprovedActors(List.copyOf(pendingApproval.getApprovedActors() == null ? List.of() : pendingApproval.getApprovedActors()));
        task.setRejectedActors(List.copyOf(pendingApproval.getRejectedActors() == null ? List.of() : pendingApproval.getRejectedActors()));
        task.setBlocked(isBlocked(pendingApproval, actorId));
        task.setBlockedByActorId(isBlocked(pendingApproval, actorId) ? pendingApproval.getPrerequisiteActorId() : null);
        task.setStartTime(resolveEventTime(latestAudit));
        if (latestAudit != null) {
            task.setAction(latestAudit.getAction());
            task.setComment(latestAudit.getComment());
            task.setClosedByActorId(latestAudit.getActorId());
        }
        if (!FlowApprovalTaskStatus.PENDING.equals(status)) {
            task.setEndTime(resolveEventTime(latestAudit));
        }
        return task;
    }

    private FlowApprovalTaskStatus resolveStatus(PendingApproval pendingApproval, String actorId) {
        if (pendingApproval.getApprovedActors() != null && pendingApproval.getApprovedActors().contains(actorId)) {
            return FlowApprovalTaskStatus.APPROVED;
        }
        if (pendingApproval.getRejectedActors() != null && pendingApproval.getRejectedActors().contains(actorId)) {
            return FlowApprovalTaskStatus.REJECTED;
        }
        return FlowApprovalTaskStatus.PENDING;
    }

    private boolean isBlocked(PendingApproval pendingApproval, String actorId) {
        return Objects.equals(actorId, pendingApproval.getBlockedActorId())
                && !isPrerequisiteResolved(pendingApproval);
    }

    private boolean isPrerequisiteResolved(PendingApproval pendingApproval) {
        return pendingApproval.getApprovedActors() != null
                && pendingApproval.getApprovedActors().contains(pendingApproval.getPrerequisiteActorId());
    }

    private static ApprovalActionAuditEntry findLatestAudit(List<ApprovalActionAuditEntry> history,
                                                             String nodeId,
                                                             Integer cycleNumber,
                                                             String actorId) {
        for (int i = history.size() - 1; i >= 0; i--) {
            ApprovalActionAuditEntry entry = history.get(i);
            if (Objects.equals(nodeId, entry.getNodeId())
                    && Objects.equals(cycleNumber, entry.getCycleNumber())
                    && Objects.equals(actorId, entry.getActorId())
                    && (ApprovalActionType.APPROVE.equals(entry.getAction()) || ApprovalActionType.REJECT.equals(entry.getAction()))) {
                return entry;
            }
        }
        return null;
    }

    private LocalDateTime resolveEventTime(ApprovalActionAuditEntry audit) {
        return audit == null || audit.getEventTime() == null
                ? LocalDateTime.now()
                : audit.getEventTime();
    }
}
