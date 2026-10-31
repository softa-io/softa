package io.softa.starter.flow.service.impl;

import java.io.Serializable;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import io.softa.framework.base.context.ContextUtils;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.i18n.I18n;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.utils.BeanTool;
import io.softa.framework.orm.utils.IdUtils;
import io.softa.starter.flow.api.FlowCompileException;
import io.softa.starter.flow.compiler.DefaultFlowCompiler;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.dto.FlowApprovalPreview;
import io.softa.starter.flow.dto.FlowRecordApprovalsView;
import io.softa.starter.flow.entity.ApprovalModel;
import io.softa.starter.flow.entity.FlowDebugHistory;
import io.softa.starter.flow.entity.FlowDesign;
import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.enums.ApprovalOutcomeType;
import io.softa.starter.flow.enums.ApprovalStatus;
import io.softa.starter.flow.runtime.engine.FlowLaunchResponse;
import io.softa.starter.flow.runtime.api.FlowStartRequest;
import io.softa.starter.flow.runtime.api.FlowWithdrawRequest;
import io.softa.starter.flow.runtime.context.RecordVariables;
import io.softa.starter.flow.runtime.api.PublishAndStartRequest;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.FlowBundleRegistry;
import io.softa.starter.flow.runtime.engine.ApprovalPathPreviewer;
import io.softa.starter.flow.runtime.engine.ApprovalStatusWriter;
import io.softa.starter.flow.runtime.engine.ApproverResolutionService;
import io.softa.starter.flow.runtime.engine.FlowRuntimeEngine;
import io.softa.starter.flow.runtime.engine.ModelApprovalHandlerRegistry;
import io.softa.starter.flow.runtime.engine.WithdrawPolicyGuard;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.exception.FlowAuthorizationException;
import io.softa.starter.flow.runtime.exception.FlowRuntimeException;
import io.softa.starter.flow.runtime.spi.ApprovalOutcome;
import io.softa.starter.flow.runtime.spi.ApprovalSubmission;
import io.softa.starter.flow.runtime.spi.ModelApprovalHandler;
import io.softa.starter.flow.runtime.spi.OrganizationService;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.runtime.trigger.DefaultFlowTriggerRegistry;
import io.softa.starter.flow.runtime.trigger.FlowAutomationService;
import io.softa.starter.flow.service.FlowDebugHistoryService;
import io.softa.starter.flow.service.FlowDesignService;
import io.softa.starter.flow.service.FlowInstanceService;
import io.softa.starter.flow.service.FlowLaunchService;
import io.softa.starter.flow.service.FlowPublishService;
import io.softa.starter.flow.service.support.FlowInstanceAccessGuard;
import io.softa.starter.flow.service.support.FlowUserNames;

/**
 * Default launch facade built on top of publish and runtime services.
 */
@Slf4j
@Service
public class FlowLaunchServiceImpl implements FlowLaunchService {

    // A submission is refused with these, and its preview reports them.
    private static final String ALREADY_IN_APPROVAL = "This record is already in approval.";
    private static final String ALREADY_APPROVED = "This record is approved and cannot be submitted again.";
    private static final String ALREADY_REJECTED = "This record was rejected and cannot be submitted again. Raise a new one.";
    private static final String ALREADY_CANCELLED = "This record was cancelled and cannot be submitted again. Raise a new one.";
    private static final String ONLY_DRAFT_CANCELLED = "Only a draft can be cancelled; this record is {0}.";
    private static final String CANCEL_IN_APPROVAL = "This record is in approval. Withdraw it first, then cancel the draft.";
    private static final String NO_SUCH_RECORD = "The {0} record {1} does not exist.";
    private static final String NO_SUBJECT = "This record names no employee in {0}, so its approvers cannot be found.";
    private static final String NO_SUBMIT_FLOW = "No approval flow applies to this {0} record. "
            + "Ask your administrator to set one up, to enable it, or to widen its submit condition.";
    private static final String SEVERAL_SUBMIT_FLOWS = "Several approval flows apply to this {0} record ({1}). Ask "
            + "your administrator to narrow their submit conditions so that exactly one applies.";

    private final FlowPublishService flowPublishService;
    private final FlowRuntimeEngine flowRuntimeEngine;
    private final FlowBundleRegistry flowBundleRegistry;
    private final DefaultFlowCompiler flowCompiler;
    private final ModelApprovalHandlerRegistry handlerRegistry;
    private final ApprovalStatusWriter statusWriter;
    private final DefaultFlowTriggerRegistry triggerRegistry;
    private final FlowInstanceService instanceService;
    private final ModelService<Serializable> modelService;
    private final ApprovalPathPreviewer pathPreviewer;
    private final FlowInstanceAccessGuard accessGuard;
    private final FlowUserNames userNames;
    private final WithdrawPolicyGuard withdrawPolicyGuard;

    @Autowired(required = false)
    private FlowDebugHistoryService debugHistoryService;

    @Autowired(required = false)
    private FlowDesignService designService;

    /** Where a submission's subject works, kept on its approval; an application without one keeps none. */
    @Autowired(required = false)
    private OrganizationService organizationService;

    @SuppressWarnings("unchecked")
    public FlowLaunchServiceImpl(FlowPublishService flowPublishService,
                                 FlowRuntimeEngine flowRuntimeEngine,
                                 FlowBundleRegistry flowBundleRegistry,
                                 DefaultFlowCompiler flowCompiler,
                                 ModelApprovalHandlerRegistry handlerRegistry,
                                 ApprovalStatusWriter statusWriter,
                                 DefaultFlowTriggerRegistry triggerRegistry,
                                 FlowInstanceService instanceService,
                                 ModelService<?> modelService,
                                 ApprovalPathPreviewer pathPreviewer,
                                 FlowInstanceAccessGuard accessGuard,
                                 FlowUserNames userNames,
                                 WithdrawPolicyGuard withdrawPolicyGuard) {
        this.flowPublishService = flowPublishService;
        this.flowRuntimeEngine = flowRuntimeEngine;
        this.flowBundleRegistry = flowBundleRegistry;
        this.flowCompiler = flowCompiler;
        this.handlerRegistry = handlerRegistry;
        this.statusWriter = statusWriter;
        this.triggerRegistry = triggerRegistry;
        this.instanceService = instanceService;
        this.modelService = (ModelService<Serializable>) modelService;
        this.pathPreviewer = pathPreviewer;
        this.accessGuard = accessGuard;
        this.userNames = userNames;
        this.withdrawPolicyGuard = withdrawPolicyGuard;
    }

    @Override
    public FlowLaunchResponse publishAndStart(PublishAndStartRequest request) {
        if (request == null || request.getDefinition() == null) {
            throw new FlowCompileException("definition is required to publish and start a flow");
        }
        CompiledFlowDefinition published = flowPublishService.publish(request.getFlowId(), request.getDefinition());
        FlowStartRequest startRequest = new FlowStartRequest();
        startRequest.setBundleId(published.getBundleId());
        startRequest.setInitiatorId(request.getInitiatorId());
        startRequest.setVariables(request.getVariables());
        FlowExecutionState state = flowRuntimeEngine.startAutomation(startRequest);
        return FlowLaunchResponse.builder()
                .bundle(published)
                .state(state)
                .build();
    }

    @Override
    public FlowLaunchResponse debugRunDraft(String flowId, FlowStartRequest request) {
        if (designService == null) {
            throw new FlowCompileException("FlowDesignService is not available; cannot load draft for a debug run");
        }
        DesignFlowDefinition definition = designService.getById(flowId)
                .map(FlowDesign::getDesignJson)
                .orElseThrow(() -> new FlowCompileException("FlowDesign not found: " + flowId));

        // compile errors carry anchored diagnostics — same surface as /validate
        CompiledFlowDefinition debugBundle = flowBundleRegistry.registerDebug(
                flowCompiler.compile(definition), definition, flowId);

        LocalDateTime startTime = LocalDateTime.now();
        FlowStartRequest startRequest = request != null ? request : new FlowStartRequest();
        startRequest.setFlowId(flowId);
        startRequest.setBundleId(debugBundle.getBundleId());
        FlowExecutionState state = flowRuntimeEngine.start(startRequest);

        recordDebugHistory(state, startRequest, startTime);

        return FlowLaunchResponse.builder()
                .bundle(debugBundle)
                .state(state)
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FlowExecutionState submitRecord(String modelName, String rowId, String initiatorId) {
        requireRecordKey(modelName, rowId, "submit");
        ModelApprovalHandler<?> handler = handlerRegistry.require(modelName);
        // Nothing moves for a record the submitter cannot reach with their own permissions.
        if (modelService.getById(modelName, IdUtils.formatId(modelName, rowId), List.of(ModelConstant.ID)).isEmpty()) {
            throw new BusinessException(NO_SUCH_RECORD, modelName, rowId);
        }
        return submit(handler, modelName, rowId, initiatorId);
    }

    private <T extends ApprovalModel> FlowExecutionState submit(ModelApprovalHandler<T> handler, String modelName,
                                                                String rowId, String initiatorId) {
        Submission<T> submission = checkSubmission(handler, modelName, rowId, latestApproval(modelName, rowId));
        // Every submission starts a new approval from the beginning — after a return too — with the
        // flow chosen again for the record as it is now.
        CompiledFlowDefinition definition = chooseSubmitFlow(modelName, submitVariables(modelName, rowId, submission));
        // Before the start: a flow without an approval step completes while it starts, and its
        // result must land on a record that is already in approval.
        statusWriter.submitted(handler, rowId, submission.record(), initiatorId);
        // The record's earlier approvals are over; this one replaces them — a returned one no longer
        // waits for the record to be edited.
        instanceService.markSuperseded(modelName, rowId);
        FlowStartRequest request = submitStartRequest(definition, modelName, rowId, initiatorId, submission);
        request.setDepartmentId(subjectDeptOf(submission.subjectId()));
        // Started as the record's approval: its result is written back, and the unique open-record
        // key refuses a second approval of the record, a concurrent submission included.
        return flowRuntimeEngine.startRecordApproval(request);
    }

    /** The subject's department as the submission finds it, read past the submitter's permissions. */
    private Long subjectDeptOf(Long subjectId) {
        return organizationService == null ? null
                : ContextUtils.withoutPermissionChecks(() -> organizationService.getDeptIdOfEmployee(subjectId));
    }

    /**
     * The checks a submission makes before its flow is chosen — first those every model shares, on the
     * record's approval status and its approvals, then its handler's — and what they worked out. Only a
     * draft is submitted. Throws the refusal, which the preview reports instead.
     */
    private <T extends ApprovalModel> Submission<T> checkSubmission(ModelApprovalHandler<T> handler,
                                                                    String modelName, String rowId,
                                                                    FlowInstance latest) {
        Map<String, Object> row = readRecord(modelName, rowId);
        ApprovalStatus status = ApprovalStatus.from(row.get(ApprovalModel.APPROVAL_STATUS));
        if (status == ApprovalStatus.PENDING || inProgress(latest)) {
            throw new BusinessException(ALREADY_IN_APPROVAL);
        }
        switch (status) {
            case APPROVED -> throw new BusinessException(ALREADY_APPROVED);
            case REJECTED -> throw new BusinessException(ALREADY_REJECTED);
            case CANCELLED -> throw new BusinessException(ALREADY_CANCELLED);
            case null, default -> {
                // A draft — or a record that never had a status, which is one.
            }
        }
        String subjectField = ModelApprovalHandlerRegistry.subjectFieldOf(handler);
        Long subjectId = longOf(row.get(subjectField));
        if (subjectId == null) {
            throw new BusinessException(NO_SUBJECT, subjectField);
        }
        T record = BeanTool.mapToObject(row, handler.modelClass());
        handler.validate(record);
        return new Submission<>(row, record, subjectId, handler.submission(record));
    }

    /**
     * A submission past its checks: the record as a row for the submit triggers and as an entity for its
     * handler, the employee it is about, and what its approval starts with.
     */
    private record Submission<T extends ApprovalModel>(Map<String, Object> row, T record, Long subjectId,
                                                       ApprovalSubmission approval) {
    }

    private static Long longOf(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null || value.toString().isBlank() ? null : Long.valueOf(value.toString());
    }

    /**
     * No transaction of its own: a refusal the handler throws through a transactional method marks
     * the transaction it joins rollback-only, and the preview reports the refusal rather than failing.
     */
    @Override
    public FlowApprovalPreview previewRecord(String modelName, String rowId, String requesterId) {
        requireRecordKey(modelName, rowId, "preview");
        // The caller's own permissions decide, before anything is read past them.
        accessGuard.requireRecordReader(modelName, rowId, requesterId);
        FlowApprovalPreview preview = previewSubmission(modelName, rowId, requesterId);
        userNames.name(actorsOf(preview));
        return preview;
    }

    /**
     * {@link #submitRecord} up to the start of the approval, whose path is worked out instead — or, for a
     * record whose approval is in progress, the rest of that approval's path.
     */
    private FlowApprovalPreview previewSubmission(String modelName, String rowId, String submitterId) {
        FlowInstance latest = latestApproval(modelName, rowId);
        if (inProgress(latest)) {
            return previewInProgress(latest, submitterId);
        }
        Submission<?> submission;
        try {
            submission = checkSubmission(handlerRegistry.require(modelName), modelName, rowId, latest);
        } catch (BusinessException refusal) {
            FlowApprovalPreview refused = notReady(FlowApprovalPreview.Status.REFUSED, refusal.getMessage());
            // A failed approval leaves its record in approval: its submitter takes it back by withdrawing it.
            refused.setWithdrawable(latest != null && FlowExecutionStatus.FAILED.equals(latest.getStatus())
                    && Objects.equals(submitterId, latest.getInitiatorId()));
            return refused;
        }
        List<CompiledFlowDefinition> flows = triggerRegistry.findSubmitFlows(modelName,
                submitVariables(modelName, rowId, submission));
        if (flows.isEmpty()) {
            return notReady(FlowApprovalPreview.Status.NO_FLOW, I18n.get(NO_SUBMIT_FLOW, modelName));
        }
        if (flows.size() > 1) {
            FlowApprovalPreview preview = notReady(FlowApprovalPreview.Status.SEVERAL_FLOWS,
                    I18n.get(SEVERAL_SUBMIT_FLOWS, modelName, flowNames(flows)));
            preview.setCandidates(flows.stream()
                    .map(flow -> new FlowApprovalPreview.Flow(flow.getFlowId(), flow.getFlowName()))
                    .toList());
            return preview;
        }
        CompiledFlowDefinition definition = flows.getFirst();
        return pathPreviewer.preview(definition, submitStartRequest(definition, modelName, rowId, submitterId,
                submission));
    }

    /**
     * The rest of an approval in progress: the step it waits on now, then those after it — and whether the
     * requester may withdraw it: its submitter, on a flow that lets them.
     */
    private FlowApprovalPreview previewInProgress(FlowInstance approval, String requesterId) {
        FlowExecutionState state = ContextUtils.withoutPermissionChecks(
                () -> flowRuntimeEngine.getInstance(approval.getInstanceId())).orElse(null);
        CompiledFlowDefinition definition = state == null ? null
                : flowBundleRegistry.getByBundleId(state.getBundleId()).orElse(null);
        if (definition == null) {
            return notReady(FlowApprovalPreview.Status.REFUSED, I18n.get(ALREADY_IN_APPROVAL));
        }
        FlowApprovalPreview preview = pathPreviewer.previewRemaining(definition, state);
        preview.setWithdrawable(withdrawPolicyGuard.withdrawable(state, requesterId));
        return preview;
    }

    private static FlowApprovalPreview notReady(FlowApprovalPreview.Status status, String message) {
        return FlowApprovalPreview.builder().status(status).message(message).build();
    }

    /** Every user the preview mentions: its steps' approvers and those counted as having approved. */
    private static List<FlowRecordApprovalsView.Actor> actorsOf(FlowApprovalPreview preview) {
        List<FlowRecordApprovalsView.Actor> actors = new ArrayList<>();
        if (preview.getSteps() != null) {
            for (FlowApprovalPreview.Step step : preview.getSteps()) {
                actors.addAll(step.getApprovers());
                step.getAutoApprovals().forEach(approval -> actors.add(approval.getActor()));
            }
        }
        return actors;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FlowExecutionState withdrawRecord(String modelName, String rowId, String actorId, String comment) {
        requireRecordKey(modelName, rowId, "withdraw");
        ModelApprovalHandler<?> handler = handlerRegistry.require(modelName);
        FlowInstance latest = latestApproval(modelName, rowId);
        if (latest != null && FlowExecutionStatus.FAILED.equals(latest.getStatus())) {
            return takeBackFailed(handler, latest, rowId, actorId, comment);
        }
        if (latest == null || latest.getStatus() == null || latest.getStatus().isTerminal()) {
            throw new BusinessException("This record has no approval in progress to withdraw.");
        }
        FlowWithdrawRequest request = new FlowWithdrawRequest();
        request.setInstanceId(latest.getInstanceId());
        request.setActorId(actorId);
        request.setComment(comment);
        // The outcome listener writes the record back as a draft in this transaction.
        return flowRuntimeEngine.withdraw(request);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelRecord(String modelName, String rowId, String actorId) {
        requireRecordKey(modelName, rowId, "cancel");
        ModelApprovalHandler<?> handler = handlerRegistry.require(modelName);
        // Nothing moves for a record the applicant cannot reach with their own permissions.
        if (modelService.getById(modelName, IdUtils.formatId(modelName, rowId), List.of(ModelConstant.ID)).isEmpty()) {
            throw new BusinessException(NO_SUCH_RECORD, modelName, rowId);
        }
        cancel(handler, modelName, rowId, actorId);
    }

    private <T extends ApprovalModel> void cancel(ModelApprovalHandler<T> handler, String modelName, String rowId,
                                                  String actorId) {
        Map<String, Object> row = readRecord(modelName, rowId);
        ApprovalStatus status = ApprovalStatus.from(row.get(ApprovalModel.APPROVAL_STATUS));
        if (status == ApprovalStatus.PENDING || inProgress(latestApproval(modelName, rowId))) {
            throw new BusinessException(CANCEL_IN_APPROVAL);
        }
        if (status != null && status != ApprovalStatus.DRAFT) {
            throw new BusinessException(ONLY_DRAFT_CANCELLED, status.getCode());
        }
        T record = BeanTool.mapToObject(row, handler.modelClass());
        handler.validateCancel(record);
        statusWriter.cancelled(handler, rowId, record, actorId);
    }

    private static void requireRecordKey(String modelName, String rowId, String action) {
        if (!StringUtils.hasText(modelName) || !StringUtils.hasText(rowId)) {
            throw new FlowActionValidationException("modelName and rowId are required to " + action + " a record");
        }
    }

    /**
     * The record's latest approval — the instance its newest submission started — or null when it was
     * never submitted. Other instances may run on the same record (automation, debug runs) and do not
     * count.
     */
    private FlowInstance latestApproval(String modelName, String rowId) {
        return ContextUtils.withoutPermissionChecks(() -> instanceService.findByModelNameAndRowId(modelName, rowId))
                .stream()
                .filter(instance -> Boolean.TRUE.equals(instance.getRecordApproval()))
                .max(Comparator.comparing(FlowInstance::getCreatedTime, Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(null);
    }

    /** Whether {@code approval} — a record's latest — is still in progress. */
    private static boolean inProgress(FlowInstance approval) {
        return approval != null && approval.getStatus() != null && !approval.getStatus().isTerminal();
    }

    /**
     * An approval that failed leaves its record in approval with nobody to act on it, so its submitter
     * takes the record back: it is written back as a draft, as withdrawn. The failed instance stays as it is, for
     * flow monitoring.
     */
    private FlowExecutionState takeBackFailed(ModelApprovalHandler<?> handler, FlowInstance failed, String rowId,
                                             String actorId, String comment) {
        if (!Objects.equals(actorId, failed.getInitiatorId())) {
            throw new FlowAuthorizationException("Only the submitter can withdraw the record after its approval failed: "
                    + failed.getModelName() + " " + rowId);
        }
        statusWriter.concluded(handler, rowId,
                new ApprovalOutcome(ApprovalOutcomeType.WITHDRAWN, failed.getInstanceId(), actorId, comment));
        return ContextUtils.withoutPermissionChecks(() -> flowRuntimeEngine.getInstance(failed.getInstanceId()))
                .orElseThrow(() -> new FlowRuntimeException("Flow instance not found: " + failed.getInstanceId()));
    }

    /** The whole record, read past the submitter's field and row checks: submit conditions see every field. */
    private Map<String, Object> readRecord(String modelName, String rowId) {
        Serializable id = IdUtils.formatId(modelName, rowId);
        return ContextUtils.withoutPermissionChecks(() -> modelService.getById(modelName, id))
                .orElseThrow(() -> new BusinessException(NO_SUCH_RECORD, modelName, rowId));
    }

    /** The approval flow of the document whose submit condition holds for the record: exactly one must. */
    private CompiledFlowDefinition chooseSubmitFlow(String modelName, Map<String, Object> variables) {
        List<CompiledFlowDefinition> flows = triggerRegistry.findSubmitFlows(modelName, variables);
        if (flows.isEmpty()) {
            throw new BusinessException(NO_SUBMIT_FLOW, modelName);
        }
        if (flows.size() > 1) {
            throw new BusinessException(SEVERAL_SUBMIT_FLOWS, modelName, flowNames(flows));
        }
        return flows.getFirst();
    }

    private static String flowNames(List<CompiledFlowDefinition> flows) {
        return flows.stream().map(CompiledFlowDefinition::getFlowName).collect(Collectors.joining(", "));
    }

    /**
     * What an approval of the record starts with, and what its flow's submit condition reads: the record's own fields,
     * then what its handler works out on top of them, with the trigger metadata every triggered start carries.
     */
    private static Map<String, Object> submitVariables(String modelName, String rowId, Submission<?> submission) {
        Map<String, Object> variables = RecordVariables.of(modelName, submission.row());
        variables.putAll(submission.approval().variables());
        // The trigger metadata every triggered start carries (see FlowAutomationService).
        variables.put("_triggerType", FlowAutomationService.SUBMIT_TRIGGER_TYPE);
        variables.put("_sourceModel", modelName);
        variables.put("_sourceRowId", rowId);
        variables.put(ApproverResolutionService.SUBJECT_ID_KEY, submission.subjectId());
        return variables;
    }

    private static FlowStartRequest submitStartRequest(CompiledFlowDefinition definition, String modelName,
                                                       String rowId, String initiatorId, Submission<?> submission) {
        ApprovalSubmission approval = submission.approval();
        Map<String, Object> variables = submitVariables(modelName, rowId, submission);
        FlowStartRequest request = new FlowStartRequest();
        request.setBundleId(definition.getBundleId());
        request.setInitiatorId(initiatorId);
        request.setTitle(approval.title());
        request.setModelName(modelName);
        request.setRowId(rowId);
        request.setVariables(variables);
        request.setSubjectId(submission.subjectId());
        request.setEffectiveDate(approval.effectiveDate());
        request.setRecordCode(approval.code());
        return request;
    }

    private void recordDebugHistory(FlowExecutionState state, FlowStartRequest request, LocalDateTime startTime) {
        if (debugHistoryService == null) {
            return;
        }
        try {
            LocalDateTime endTime = LocalDateTime.now();
            FlowDebugHistory history = new FlowDebugHistory();
            history.setFlowId(state.getFlowId());
            history.setFlowRevision(state.getFlowRevision());
            history.setInstanceId(state.getInstanceId());
            history.setStatus(state.getStatus());
            history.setInitiatorId(state.getInitiatorId());
            history.setStartTime(startTime);
            history.setEndTime(endTime);
            history.setDurationMs(Duration.between(startTime, endTime).toMillis());
            history.setEventMessage(JsonUtils.objectToString(request.getVariables()));
            history.setNodeTrace(JsonUtils.objectToString(state.getTrace()));
            history.setFinalVariables(JsonUtils.objectToString(state.getVariables()));
            history.setErrorMessage(state.getErrorMessage());
            debugHistoryService.recordHistory(history);
        } catch (Exception e) {
            log.error("Failed to record debug history: {}", e.getMessage(), e);
        }
    }
}
