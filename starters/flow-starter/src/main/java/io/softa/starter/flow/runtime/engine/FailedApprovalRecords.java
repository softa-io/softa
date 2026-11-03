package io.softa.starter.flow.runtime.engine;

import java.io.Serializable;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

import io.softa.framework.base.context.ContextUtils;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.utils.IdUtils;
import io.softa.starter.flow.entity.ApprovalModel;
import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.enums.ApprovalStatus;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.service.FlowInstanceService;

/**
 * Where a record stands against an approval of it that failed (zingkey-hcm#2385): still waiting on it — in approval,
 * that approval its latest — or moved on, taken back by its submitter (a draft again) or submitted again (waiting on
 * its new approval). Only while the record waits may the steps after the approval run again, or its submitter take it
 * back: otherwise they would act on a record that moved on. Read past the caller's permissions: the question is what
 * became of the record, not who may see it.
 */
@Component
public class FailedApprovalRecords {

    /** Where the record stands against its failed approval. */
    public enum Standing {
        /** In approval, the failed approval its latest. */
        WAITS,
        /** Submitted again: it waits on its new approval. */
        SUBMITTED_AGAIN,
        /** No longer in approval: taken back by its submitter, or removed. */
        MOVED_ON
    }

    private final FlowInstanceService instanceService;
    private final ModelService<Serializable> modelService;

    @SuppressWarnings("unchecked")
    public FailedApprovalRecords(FlowInstanceService instanceService, ModelService<?> modelService) {
        this.instanceService = instanceService;
        this.modelService = (ModelService<Serializable>) modelService;
    }

    /** Where the record of {@code state} — a record's approval — stands against it. */
    public Standing standingOf(FlowExecutionState state) {
        List<FlowInstance> approvals = ContextUtils.withoutPermissionChecks(
                () -> instanceService.findByModelNameAndRowId(state.getModelName(), state.getRowId()));
        FlowInstance latest = approvals.stream()
                .filter(instance -> Boolean.TRUE.equals(instance.getRecordApproval()))
                .max(Comparator.comparing(FlowInstance::getCreatedTime, Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(null);
        if (latest != null && !Objects.equals(latest.getInstanceId(), state.getInstanceId())) {
            return Standing.SUBMITTED_AGAIN;
        }
        Serializable id = IdUtils.formatId(state.getModelName(), state.getRowId());
        Map<String, Object> row = ContextUtils.withoutPermissionChecks(
                () -> modelService.getById(state.getModelName(), id, List.of(ApprovalModel.APPROVAL_STATUS))).orElse(null);
        return row != null && ApprovalStatus.from(row.get(ApprovalModel.APPROVAL_STATUS)) == ApprovalStatus.PENDING
                ? Standing.WAITS
                : Standing.MOVED_ON;
    }
}
