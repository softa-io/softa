package io.softa.starter.flow.runtime.engine;

import java.io.Serializable;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.framework.base.context.ContextUtils;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.utils.BeanTool;
import io.softa.framework.orm.utils.IdUtils;
import io.softa.starter.flow.entity.ApprovalModel;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.context.RecordVariables;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.spi.ApprovalSubmission;
import io.softa.starter.flow.runtime.spi.ModelApprovalHandler;
import io.softa.starter.flow.runtime.state.FlowExecutionState;

import static io.softa.framework.orm.constant.ModelConstant.ID;

/**
 * Enforces approval-node form field permissions against the originating business row.
 *
 * <p>On an approve: every field the node marks REQUIRED must hold a value once the edits land — the
 * value the approver sends, or else the one the row already stores — or the transition is blocked,
 * form data or not. Of the edits, only the fields the node marks EDITABLE or REQUIRED are kept (HIDDEN,
 * READONLY and unlisted fields are dropped; a node without {@code formPermissions} writes nothing), and
 * they are written back to the business row via {@link ModelService#updateByFilter}. Because this is a
 * <em>partial</em> update (editable fields only), every other column is simply left untouched.
 *
 * <p>A record's approval then holds the edits to its model's own rules — its handler's
 * {@code afterApproverEdit}, which may refuse the approve — and routes on the record as the edits left it: its
 * handler's {@code submission} is worked out again and its variables replace the ones the flow started with, so a
 * later condition sees what the approver changed.
 *
 * <p>The write rides the framework model RPC: {@code @RPCCheckpoint} + {@code SwitchServiceAspect} route
 * it to the owning app by appCode in a microservice topology, and run it in-JVM in a monolith.
 */
@Component
public class ApprovalFormWriteService {

    private final ModelService<Serializable> modelService;
    private final ModelApprovalHandlerRegistry handlerRegistry;

    @SuppressWarnings("unchecked")
    public ApprovalFormWriteService(ModelService<?> modelService, ModelApprovalHandlerRegistry handlerRegistry) {
        this.modelService = (ModelService<Serializable>) modelService;
        this.handlerRegistry = handlerRegistry;
    }

    /**
     * Validate + sanitize + write the form edits carried by an approve, then refresh the variables of a
     * record's approval. Nothing is written when no editable field is sent or no business row is bound to
     * the instance.
     *
     * @throws FlowActionValidationException if a REQUIRED field is missing or blank
     */
    public void applyFormData(CompiledFlowDefinition definition, FlowExecutionState state, CompiledFlowNode node,
                              Map<String, Object> formData) {
        boolean bound = StringUtils.hasText(state.getModelName()) && StringUtils.hasText(state.getRowId());
        Map<String, Object> sanitized = FormPermissionService.filterFormData(node, formData);
        List<String> required = FormPermissionService.requiredFields(node);
        if (!required.isEmpty()) {
            Map<String, Object> resulting = new HashMap<>(bound ? stored(state, required) : Map.of());
            resulting.putAll(sanitized);
            List<String> errors = FormPermissionService.validateFormData(node, resulting);
            if (!errors.isEmpty()) {
                throw new FlowActionValidationException("Form validation failed: " + String.join("; ", errors));
            }
        }
        // No business row bound to the instance (e.g. a notification-only flow) -> nothing to write.
        if (!bound || sanitized.isEmpty()) {
            return;
        }
        modelService.updateByFilter(state.getModelName(), new Filters().eq(ID, state.getRowId()), sanitized);
        if (state.isApprovalOfRecord()) {
            handlerRegistry.find(state.getModelName()).ifPresent(handler -> {
                holdToModelRules(handler, state, Set.copyOf(sanitized.keySet()));
                refreshVariables(handler, definition, state);
            });
        }
    }

    /** The values the business row stores in {@code fields}. */
    private Map<String, Object> stored(FlowExecutionState state, List<String> fields) {
        Serializable id = IdUtils.formatId(state.getModelName(), state.getRowId());
        return ContextUtils.withoutPermissionChecks(() -> modelService.getById(state.getModelName(), id, fields))
                .orElse(Map.of());
    }

    /**
     * The record as the edits left it, held to its model's own rules on an approver's edits; throwing refuses the
     * approve, the edits with it.
     */
    private <T extends ApprovalModel> void holdToModelRules(ModelApprovalHandler<T> handler, FlowExecutionState state,
                                                            Set<String> editedFields) {
        Serializable id = IdUtils.formatId(state.getModelName(), state.getRowId());
        ContextUtils.withoutPermissionChecks(() -> modelService.getById(state.getModelName(), id)).ifPresent(row ->
                handler.afterApproverEdit(BeanTool.mapToObject(row, handler.modelClass()), editedFields));
    }

    /**
     * The record as the edits left it — with the fields its flow's rules read up to {@link RecordVariables#MAX_HOPS}
     * relations on, read again — and its handler's on top: the variables its flow now routes on.
     */
    private <T extends ApprovalModel> void refreshVariables(ModelApprovalHandler<T> handler,
                                                            CompiledFlowDefinition definition, FlowExecutionState state) {
        Serializable id = IdUtils.formatId(state.getModelName(), state.getRowId());
        ContextUtils.withoutPermissionChecks(() -> modelService.getById(state.getModelName(), id)).ifPresent(row -> {
            Map<String, Object> variables = new LinkedHashMap<>(state.getVariables() == null ? Map.of() : state.getVariables());
            Set<String> paths = definition.rulePaths();
            // A field the edits cleared no longer routes on its old value, nor a relation they cleared on its record's.
            RecordVariables.routedFields(state.getModelName()).forEach(field -> variables.remove(field.getFieldName()));
            paths.forEach(variables::remove);
            variables.putAll(RecordVariables.of(state.getModelName(), row));
            variables.putAll(RecordVariables.readPaths(modelService, state.getModelName(), state.getRowId(), row, paths));
            ApprovalSubmission submission = handler.submission(BeanTool.mapToObject(row, handler.modelClass()));
            variables.putAll(submission.variables());
            state.setVariables(variables);
            // What the lists say the record asks for follows the edits too.
            state.setSummary(submission.summary());
        });
    }
}
