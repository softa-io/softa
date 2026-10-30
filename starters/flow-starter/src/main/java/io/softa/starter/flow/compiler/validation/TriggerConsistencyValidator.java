package io.softa.starter.flow.compiler.validation;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.util.StringUtils;

import io.softa.framework.orm.compute.ComputeUtils;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.starter.flow.api.CompileDiagnostic;
import io.softa.starter.flow.compiler.FlowGraphContext;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.trigger.ApiTrigger;
import io.softa.starter.flow.design.trigger.CronTrigger;
import io.softa.starter.flow.design.trigger.EntityChangeTrigger;
import io.softa.starter.flow.design.trigger.FieldChangeTrigger;
import io.softa.starter.flow.design.trigger.SubflowTrigger;
import io.softa.starter.flow.design.trigger.SubmitTrigger;
import io.softa.starter.flow.design.trigger.TriggerSource;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.runtime.engine.FlowCron;

/**
 * Validates the trigger a flow declares against its {@link FlowScenario}.
 * <p>
 * Every flow declares one, and the trigger is the flow's start contract: a {@code Submit} flow starts
 * only by record submission, an {@code Api} flow only by an explicit start, a {@code Subflow} flow only
 * from a parent's Subflow node, a {@code Cron} flow only on its own schedule, and {@code EntityChange} /
 * {@code FieldChange} flows only by their events. An explicit start and a Subflow call both run an
 * Automation flow, so {@code Api} and {@code Subflow} belong to Automation alone.
 * <p>
 * The flow's model goes with its trigger: a trigger that starts on a record (submit, change, field change)
 * needs it, and one that starts on none (schedule, API call, parent flow) must have none.
 * <p>
 * What the trigger says about that model is checked against it: a change trigger's condition — a rule over the
 * record, checked like an edge's, or an expression, never both — and the fields a field-change trigger watches.
 * A schedule is one the cron scheduler runs and that fires again.
 */
public class TriggerConsistencyValidator implements FlowValidator {

    private static final String MISSING_TRIGGER         = "MISSING_TRIGGER";
    private static final String TRIGGER_NOT_ALLOWED     = "TRIGGER_NOT_ALLOWED";
    private static final String MISSING_MODEL           = "MISSING_MODEL";
    private static final String MODEL_WITHOUT_RECORD    = "MODEL_WITHOUT_RECORD";
    private static final String CRON_MISSING_EXPRESSION = "CRON_MISSING_EXPRESSION";
    private static final String CRON_INVALID_EXPRESSION = "CRON_INVALID_EXPRESSION";
    private static final String CONFLICTING_TRIGGER_CONDITIONS = "CONFLICTING_TRIGGER_CONDITIONS";
    private static final String INVALID_TRIGGER_CONDITION = "INVALID_TRIGGER_CONDITION";
    private static final String UNKNOWN_TRIGGER_FIELD = "UNKNOWN_TRIGGER_FIELD";

    private static final Map<FlowScenario, List<Class<? extends TriggerSource>>> ALLOWED_TRIGGERS =
            new EnumMap<>(Map.of(
                    FlowScenario.APPROVAL, List.of(SubmitTrigger.class),
                    FlowScenario.AUTOMATION, List.of(
                            EntityChangeTrigger.class, CronTrigger.class, ApiTrigger.class, SubflowTrigger.class),
                    FlowScenario.VALIDATION, List.of(EntityChangeTrigger.class, CronTrigger.class),
                    FlowScenario.COMPUTE, List.of(FieldChangeTrigger.class, EntityChangeTrigger.class, CronTrigger.class)));

    @Override
    public List<CompileDiagnostic> validate(DesignFlowDefinition definition, FlowGraphContext context) {
        List<CompileDiagnostic> d = new ArrayList<>();
        FlowScenario scenario = definition.getScenario();
        TriggerSource trigger = definition.getTrigger();
        // A missing scenario is reported as MISSING_SCENARIO; there is nothing to check the trigger against.
        if (scenario != null) {
            List<Class<? extends TriggerSource>> allowed = ALLOWED_TRIGGERS.get(scenario);
            if (trigger == null) {
                d.add(CompileDiagnostic.flowLevel(MISSING_TRIGGER, "A '" + scenario.getType()
                        + "' flow must declare its trigger: " + typeNames(allowed)));
            } else if (!allowed.contains(trigger.getClass())) {
                d.add(CompileDiagnostic.flowLevel(TRIGGER_NOT_ALLOWED, "A '" + scenario.getType()
                        + "' flow cannot use a '" + typeName(trigger.getClass()) + "' trigger; it takes: "
                        + typeNames(allowed)));
            }
        }
        if (trigger != null) {
            boolean hasModel = StringUtils.hasText(definition.getModelName());
            if (trigger.onRecord() && !hasModel) {
                d.add(CompileDiagnostic.flowLevel(MISSING_MODEL, "A '" + typeName(trigger.getClass())
                        + "' trigger starts the flow on a record: the flow must name its model"));
            } else if (!trigger.onRecord() && hasModel) {
                d.add(CompileDiagnostic.flowLevel(MODEL_WITHOUT_RECORD, "A '" + typeName(trigger.getClass())
                        + "' trigger starts the flow on no record, yet the flow names the model '"
                        + definition.getModelName() + "'"));
            }
        }
        switch (trigger) {
            case CronTrigger cron -> validateCronExpression(cron.cronExpression(), d);
            case EntityChangeTrigger change -> validateCondition(definition.getModelName(), change, d);
            case FieldChangeTrigger fieldChange -> validateWatchedFields(definition.getModelName(), fieldChange, d);
            case null, default -> {
            }
        }
        return d;
    }

    /**
     * The expression must be one the cron scheduler can run, and one that fires again: it is parsed the way the
     * scheduler parses it, so a flow that publishes is one that gets scheduled.
     */
    private static void validateCronExpression(String cronExpression, List<CompileDiagnostic> d) {
        if (!StringUtils.hasText(cronExpression)) {
            d.add(CompileDiagnostic.flowLevel(CRON_MISSING_EXPRESSION, "Cron trigger must define a cron expression"));
            return;
        }
        String refusal = FlowCron.refusal(cronExpression);
        if (refusal != null) {
            d.add(CompileDiagnostic.flowLevel(CRON_INVALID_EXPRESSION,
                    "The Cron trigger's expression '" + cronExpression + "' cannot schedule the flow: " + refusal));
        }
    }

    /**
     * A change trigger's condition is a rule over the record or an expression, never both. The rule is checked like
     * an edge's, against the fields of the flow's model; the expression is compiled.
     */
    private static void validateCondition(String modelName, EntityChangeTrigger trigger, List<CompileDiagnostic> d) {
        boolean rule = !Filters.isEmpty(trigger.condition());
        String expression = trigger.conditionExpression();
        if (rule && StringUtils.hasText(expression)) {
            d.add(CompileDiagnostic.flowLevel(CONFLICTING_TRIGGER_CONDITIONS,
                    "The trigger has both a rule condition and an expression; keep one"));
        } else if (rule && StringUtils.hasText(modelName)) {
            if (!ModelManager.existModel(modelName)) {
                d.add(CompileDiagnostic.flowLevel(INVALID_TRIGGER_CONDITION, "The trigger's rule reads the fields of '"
                        + modelName + "', which is not a model of this application"));
                return;
            }
            try {
                GatewayValidator.checkRule(trigger.condition(), modelName, "The trigger's rule: ");
            } catch (IllegalStateException e) {
                d.add(CompileDiagnostic.flowLevel(INVALID_TRIGGER_CONDITION, e.getMessage()));
            }
        } else if (StringUtils.hasText(expression) && !ComputeUtils.validateExpression(expression)) {
            d.add(CompileDiagnostic.flowLevel(INVALID_TRIGGER_CONDITION,
                    "The trigger's condition expression does not compile: " + expression));
        }
    }

    /** A field-change trigger watches fields its model has; a model the application does not hold is not checked. */
    private static void validateWatchedFields(String modelName, FieldChangeTrigger trigger, List<CompileDiagnostic> d) {
        if (trigger.fieldNames() == null || !StringUtils.hasText(modelName) || !ModelManager.existModel(modelName)) {
            return;
        }
        List<String> unknown = trigger.fieldNames().stream()
                .filter(field -> !ModelManager.existField(modelName, field))
                .toList();
        if (!unknown.isEmpty()) {
            d.add(CompileDiagnostic.flowLevel(UNKNOWN_TRIGGER_FIELD, "The trigger watches fields '" + modelName
                    + "' does not have: " + String.join(", ", unknown)));
        }
    }

    /** The {@code type} discriminator a trigger class serializes with: its name without "Trigger". */
    private static String typeName(Class<? extends TriggerSource> type) {
        return type.getSimpleName().replace("Trigger", "");
    }

    private static String typeNames(List<Class<? extends TriggerSource>> types) {
        return types.stream().map(TriggerConsistencyValidator::typeName).collect(Collectors.joining(", "));
    }
}
