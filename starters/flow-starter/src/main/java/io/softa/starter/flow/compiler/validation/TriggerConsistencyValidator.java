package io.softa.starter.flow.compiler.validation;

import java.util.ArrayList;
import java.util.List;

import io.softa.starter.flow.api.CompileDiagnostic;
import io.softa.starter.flow.compiler.FlowGraphContext;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.trigger.FieldChangeTrigger;
import io.softa.starter.flow.design.trigger.TriggerSource;
import io.softa.starter.flow.enums.FlowScenario;

/**
 * Validates trigger/scenario consistency using {@link TriggerSource} sealed
 * interface sub-types and {@link FlowScenario}.
 */
public class TriggerConsistencyValidator implements FlowValidator {

    private static final String FIELD_CHANGE_WRONG_SCENARIO  = "FIELD_CHANGE_WRONG_SCENARIO";
    private static final String COMPUTE_MISSING_TRIGGER       = "COMPUTE_MISSING_TRIGGER";
    private static final String VALIDATION_MISSING_TRIGGER    = "VALIDATION_MISSING_TRIGGER";
    private static final String PROCESS_FIELD_CHANGE_TRIGGER  = "PROCESS_FIELD_CHANGE_TRIGGER";

    @Override
    public List<CompileDiagnostic> validate(DesignFlowDefinition definition, FlowGraphContext context) {
        List<CompileDiagnostic> d = new ArrayList<>();
        TriggerSource trigger = definition.getTrigger();
        FlowScenario scenario = definition.getScenario();

        if (trigger instanceof FieldChangeTrigger) {
            if (!FlowScenario.COMPUTE.equals(scenario)) {
                d.add(CompileDiagnostic.flowLevel(FIELD_CHANGE_WRONG_SCENARIO,
                        "FieldChange trigger is only allowed for Compute scenario flows, "
                        + "but scenario is '" + (scenario != null ? scenario.getType() : "null") + "'"));
            }
        }

        if (FlowScenario.COMPUTE.equals(scenario) && trigger == null) {
            d.add(CompileDiagnostic.flowLevel(COMPUTE_MISSING_TRIGGER, "Compute scenario flow must define a trigger"));
        }

        if (FlowScenario.VALIDATION.equals(scenario) && trigger == null) {
            d.add(CompileDiagnostic.flowLevel(VALIDATION_MISSING_TRIGGER, "Validation scenario flow must define a trigger"));
        }

        if (FlowScenario.PROCESS.equals(scenario) && trigger instanceof FieldChangeTrigger) {
            d.add(CompileDiagnostic.flowLevel(PROCESS_FIELD_CHANGE_TRIGGER,
                    "Process scenario flow cannot use FieldChange as its primary trigger"));
        }

        return d;
    }
}
