package io.softa.starter.flow.runtime.engine;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import io.softa.framework.base.context.ContextUtils;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.utils.BeanTool;
import io.softa.framework.orm.utils.IdUtils;
import io.softa.starter.flow.entity.ApprovalModel;
import io.softa.starter.flow.enums.ApprovalOutcomeType;
import io.softa.starter.flow.enums.ApprovalStatus;
import io.softa.starter.flow.runtime.spi.ApprovalOutcome;
import io.softa.starter.flow.runtime.spi.ModelApprovalHandler;

import static io.softa.starter.flow.runtime.engine.ModelApprovalHandlerRegistry.modelNameOf;

/**
 * The single writer of a record's approval fields, and the one place that hands the record to its handler
 * around each move: {@code Pending} with its submitter and time on submission, then
 * {@link ModelApprovalHandler#afterSubmitted}; the result when its approval ends — a draft again after a
 * return or a withdrawal — then {@link ModelApprovalHandler#afterOutcome}; {@code Cancelled} when its
 * applicant cancels the draft, then {@link ModelApprovalHandler#afterOutcome} too. The writes are the
 * system's — permission checks skipped — which is what takes them past {@link ApprovalLockValidator}.
 */
@Slf4j
@Component
public class ApprovalStatusWriter {

    private final ModelService<Serializable> modelService;

    @SuppressWarnings("unchecked")
    public ApprovalStatusWriter(ModelService<?> modelService) {
        this.modelService = (ModelService<Serializable>) modelService;
    }

    /**
     * Moves a submitted record into approval — {@code Pending}, submitted by {@code submitterId}, now — and
     * hands it to its handler. Runs in the submission's transaction, just before its approval starts: a
     * flow without an approval step completes while it starts, and its result must land on a record that
     * is already in approval.
     *
     * @param record the record as the submission's checks read it; its approval fields are set here too
     */
    public <T extends ApprovalModel> void submitted(ModelApprovalHandler<T> handler, String rowId, T record,
                                                    String submitterId) {
        String modelName = modelNameOf(handler);
        record.setApprovalStatus(ApprovalStatus.PENDING);
        record.setSubmitterId(Long.valueOf(submitterId));
        record.setSubmittedTime(LocalDateTime.now());
        Map<String, Object> patch = new HashMap<>();
        patch.put(ModelConstant.ID, IdUtils.formatId(modelName, rowId));
        patch.put(ApprovalModel.APPROVAL_STATUS, ApprovalStatus.PENDING.getCode());
        patch.put(ApprovalModel.SUBMITTER_ID, record.getSubmitterId());
        patch.put(ApprovalModel.SUBMITTED_TIME, record.getSubmittedTime());
        ContextUtils.withoutPermissionChecks(() -> {
            modelService.updateOne(modelName, patch);
            handler.afterSubmitted(record);
        });
    }

    /**
     * Cancels a draft for its applicant — {@code Cancelled}, for good — and hands it to its handler as a
     * {@code CANCELLED} result with no instance. Runs in the cancellation's transaction, so a handler that
     * throws refuses it.
     *
     * @param record the draft as the cancellation's checks read it; its status is set here too
     */
    public <T extends ApprovalModel> void cancelled(ModelApprovalHandler<T> handler, String rowId, T record) {
        String modelName = modelNameOf(handler);
        record.setApprovalStatus(ApprovalStatus.CANCELLED);
        Map<String, Object> patch = new HashMap<>();
        patch.put(ModelConstant.ID, IdUtils.formatId(modelName, rowId));
        patch.put(ApprovalModel.APPROVAL_STATUS, ApprovalStatus.CANCELLED.getCode());
        ContextUtils.withoutPermissionChecks(() -> {
            modelService.updateOne(modelName, patch);
            handler.afterOutcome(record, new ApprovalOutcome(ApprovalOutcomeType.CANCELLED, null));
        });
    }

    /**
     * Writes how a record's approval ended onto the record and hands it to its handler — only while the
     * record is in approval. A return or a withdrawal writes it back as a draft; every other result is
     * final. A result delivered again finds the record moved on, and nothing happens; so does one whose
     * record is gone, deleted softly too. Runs inside the action that produced the result, so a handler that
     * throws vetoes the action.
     */
    public void concluded(ModelApprovalHandler<?> handler, String rowId, ApprovalOutcome outcome) {
        ContextUtils.withoutPermissionChecks(() -> conclude(handler, rowId, outcome));
    }

    private <T extends ApprovalModel> void conclude(ModelApprovalHandler<T> handler, String rowId,
                                                    ApprovalOutcome outcome) {
        String modelName = modelNameOf(handler);
        Serializable id = IdUtils.formatId(modelName, rowId);
        // A soft delete leaves the row, flagged, and the read by id returns it: gone all the same.
        Map<String, Object> row = modelService.getById(modelName, id)
                .filter(found -> !ApprovalModel.isDeleted(modelName, found))
                .orElse(null);
        ApprovalStatus current = row == null ? null : ApprovalStatus.from(row.get(ApprovalModel.APPROVAL_STATUS));
        if (current != ApprovalStatus.PENDING) {
            log.debug("Approval {} of {} {} ended {}, but the record is {}: nothing is written",
                    outcome.instanceId(), modelName, rowId, outcome.type(), row == null ? "gone" : current);
            return;
        }
        ApprovalStatus result = ApprovalStatus.resultOf(outcome.type());
        Map<String, Object> patch = new HashMap<>();
        patch.put(ModelConstant.ID, id);
        patch.put(ApprovalModel.APPROVAL_STATUS, result.getCode());
        modelService.updateOne(modelName, patch);
        T record = BeanTool.mapToObject(row, handler.modelClass());
        record.setApprovalStatus(result);
        handler.afterOutcome(record, outcome);
    }
}
