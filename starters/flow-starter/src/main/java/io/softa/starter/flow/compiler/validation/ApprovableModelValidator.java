package io.softa.starter.flow.compiler.validation;

import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.starter.flow.api.CompileDiagnostic;
import io.softa.starter.flow.compiler.FlowGraphContext;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.runtime.engine.ModelApprovalHandlerRegistry;

/**
 * An Approval flow approves a document the application can approve: one whose model has a
 * {@code ModelApprovalHandler} in code — what lets its records be submitted, and what writes the outcome back. A
 * flow on any other model would publish and never start. Registered as a bean, so the designer's validation and
 * every publish run it; the built-in validators know no application.
 */
@Component
public class ApprovableModelValidator implements FlowValidator {

    static final String MODEL_NOT_APPROVABLE = "MODEL_NOT_APPROVABLE";

    private final ModelApprovalHandlerRegistry handlers;

    public ApprovableModelValidator(ModelApprovalHandlerRegistry handlers) {
        this.handlers = handlers;
    }

    @Override
    public List<CompileDiagnostic> validate(DesignFlowDefinition definition, FlowGraphContext context) {
        String modelName = definition.getTrigger() == null ? null : definition.getTrigger().modelName();
        if (!FlowScenario.APPROVAL.equals(definition.getScenario()) || !StringUtils.hasText(modelName)
                || handlers.find(modelName).isPresent()) {
            return List.of();
        }
        return List.of(CompileDiagnostic.flowLevel(MODEL_NOT_APPROVABLE, "Records of '" + modelName
                + "' cannot be approved: the application has no approval handler for them"));
    }
}
