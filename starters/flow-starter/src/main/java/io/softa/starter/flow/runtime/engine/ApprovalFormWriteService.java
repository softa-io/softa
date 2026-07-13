package io.softa.starter.flow.runtime.engine;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.state.FlowExecutionState;

import static io.softa.framework.orm.constant.ModelConstant.ID;

/**
 * Enforces approval-node form field permissions against the originating business row.
 *
 * <p>On an approve carrying {@code formData}: validates REQUIRED fields (blocking the transition on
 * failure), drops HIDDEN and READONLY edits, and writes the sanitized remainder back to the business
 * row via {@link ModelService#updateByFilter}. Because this is a <em>partial</em> update (editable
 * fields only), HIDDEN/READONLY columns are simply left untouched — no original-row read is needed.
 * The write rides the framework model RPC: {@code @RPCCheckpoint} + {@code SwitchServiceAspect} route
 * it to the owning app by appCode in a microservice topology, and run it in-JVM in a monolith.
 */
@Component
public class ApprovalFormWriteService {

    private final ModelService<?> modelService;

    public ApprovalFormWriteService(ModelService<?> modelService) {
        this.modelService = modelService;
    }

    /**
     * Validate + sanitize + write the form edits carried by an approve. No-op when no form data is
     * submitted or no business row is bound to the instance.
     *
     * @throws FlowActionValidationException if a REQUIRED field is missing or blank.
     */
    public void applyFormData(FlowExecutionState state, CompiledFlowNode node, Map<String, Object> formData) {
        if (formData == null || formData.isEmpty()) {
            return;
        }
        List<String> errors = FormPermissionService.validateFormData(node, formData);
        if (!errors.isEmpty()) {
            throw new FlowActionValidationException("Form validation failed: " + String.join("; ", errors));
        }
        // No business row bound to the instance (e.g. a notification-only flow) -> nothing to write.
        if (!StringUtils.hasText(state.getModelName()) || !StringUtils.hasText(state.getRowId())) {
            return;
        }
        Map<String, Object> sanitized = FormPermissionService.filterFormData(node, formData);
        if (sanitized.isEmpty()) {
            return;
        }
        modelService.updateByFilter(state.getModelName(), new Filters().eq(ID, state.getRowId()), sanitized);
    }
}
