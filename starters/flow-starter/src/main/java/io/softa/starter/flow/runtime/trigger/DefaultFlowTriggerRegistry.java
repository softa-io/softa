package io.softa.starter.flow.runtime.trigger;

import java.util.*;
import org.springframework.stereotype.Component;

import io.softa.framework.base.context.ContextHolder;
import io.softa.starter.flow.design.trigger.*;
import io.softa.starter.flow.message.ChangeLogTriggerMapper;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.FlowBundleRegistry;
import io.softa.starter.flow.runtime.engine.FlowGatewayRouter;

/**
 * Default trigger registry that matches the bundles the registry offers for an event's
 * model by trigger type, source model, change events and fields, and the trigger's condition.
 */
@Component
public class DefaultFlowTriggerRegistry {

    static final String PARAM_CHANGED_FIELDS = "_changedFields";

    /**
     * A flow an event starts: its trigger fits the event and its condition holds — or the condition could not be
     * judged, {@code error} saying why. Such a flow is not run, and its caller records the failure: a condition that
     * cannot be evaluated is a fault someone gets to see, never a quiet "no".
     */
    public record TriggerMatch(CompiledFlowDefinition definition, String error) {

        /** A flow whose condition holds, or that has none. */
        public static TriggerMatch holding(CompiledFlowDefinition definition) {
            return new TriggerMatch(definition, null);
        }

        public boolean holds() {
            return error == null;
        }
    }

    private final FlowBundleRegistry bundleRegistry;

    public DefaultFlowTriggerRegistry(FlowBundleRegistry bundleRegistry) {
        this.bundleRegistry = bundleRegistry;
    }

    /** The flows the event starts: those {@link #findMatches} finds whose condition holds. */
    public List<CompiledFlowDefinition> findMatchingFlows(FlowTriggerEvent event) {
        return findMatches(event).stream().filter(TriggerMatch::holds).map(TriggerMatch::definition).toList();
    }

    /**
     * The flows whose trigger fits the event — its type, the flow's model, the change events, the changed fields —
     * and whose condition holds over the variables the flow would start with ({@link FlowTriggerEvent#startVariables});
     * with them, those whose condition could not be evaluated, each with its error. A condition that does not hold
     * leaves its flow out.
     */
    public List<TriggerMatch> findMatches(FlowTriggerEvent event) {
        // Candidates are scoped to the event's tenant (plus platform-shared flows), which must be
        // in context — the consumers restore it from the message. The tenant check below still
        // holds the line for a registry that offers more.
        Long currentTenant = ContextHolder.existContext() ? ContextHolder.getContext().getTenantId() : null;
        Map<String, Object> variables = null;
        List<TriggerMatch> matches = new ArrayList<>();
        for (CompiledFlowDefinition definition : bundleRegistry.listTriggerCandidates(event.getSourceModel())) {
            if (!tenantMatches(definition, currentTenant) || !fits(definition, event)) {
                continue;
            }
            if (!(definition.getTrigger() instanceof EntityChangeTrigger trigger)) {
                matches.add(TriggerMatch.holding(definition));
                continue;
            }
            if (variables == null) {
                variables = event.startVariables();
            }
            try {
                if (FlowGatewayRouter.conditionHolds(definition.getModelName(), trigger.condition(),
                        trigger.conditionExpression(), variables)) {
                    matches.add(TriggerMatch.holding(definition));
                }
            } catch (RuntimeException e) {
                matches.add(new TriggerMatch(definition, "The trigger condition of flow '" + definition.getFlowName()
                        + "' could not be evaluated: " + e.getMessage()));
            }
        }
        return matches;
    }

    /**
     * The current tenant's enabled approval flows (plus platform-shared ones) of {@code modelName} — a document's
     * one approval flow, the one a submission of its records goes to. The caller decides what none, or several
     * (left over from before a document had one), mean.
     *
     * @param modelName the submitted record's model
     */
    public List<CompiledFlowDefinition> findSubmitFlows(String modelName) {
        Long currentTenant = ContextHolder.existContext() ? ContextHolder.getContext().getTenantId() : null;
        List<CompiledFlowDefinition> matches = new ArrayList<>();
        for (CompiledFlowDefinition definition : bundleRegistry.listTriggerCandidates(modelName)) {
            if (tenantMatches(definition, currentTenant)
                    && definition.getTrigger() instanceof SubmitTrigger
                    && Objects.equals(definition.getModelName(), modelName)) {
                matches.add(definition);
            }
        }
        return matches;
    }

    /**
     * The Cron-triggered flow a schedule fired for: revision {@code bundleId}, the one its cron job was
     * scheduled from, while it is still its flow's active revision — the job of a revision a publish or a
     * rollback has replaced starts nothing in the moments before the scheduler drops it. Like any bundle, it
     * is found only in its own tenant, or in every tenant when platform-shared.
     */
    public Optional<CompiledFlowDefinition> findCronFlow(Long bundleId) {
        return bundleRegistry.getByBundleId(bundleId)
                .filter(definition -> definition.getTrigger() instanceof CronTrigger)
                .filter(definition -> bundleRegistry.getActiveByFlowId(definition.getFlowId())
                        .map(active -> Objects.equals(active.getBundleId(), bundleId))
                        .orElse(false));
    }

    /**
     * Whether any flow in the current context watches entity changes of {@code model} — lets the
     * change-log paths skip mapping rows nothing listens to.
     */
    public boolean hasEntityChangeTrigger(String model) {
        for (CompiledFlowDefinition definition : bundleRegistry.listTriggerCandidates(model)) {
            if (definition.getTrigger() instanceof EntityChangeTrigger && Objects.equals(definition.getModelName(), model)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Match-time tenant isolation: a platform-shared flow ({@code tenantId == 0}) applies to
     * every tenant; otherwise the flow's tenant must equal the event's current tenant.
     */
    private boolean tenantMatches(CompiledFlowDefinition definition, Long currentTenant) {
        Long flowTenant = definition.getTenantId();
        if (FlowBundleRegistry.SHARED_TENANT_ID.equals(flowTenant)) {
            return true;
        }
        return Objects.equals(flowTenant, currentTenant);
    }

    /** Whether the flow's trigger fits the event, its condition aside. */
    private boolean fits(CompiledFlowDefinition definition, FlowTriggerEvent event) {
        TriggerSource trigger = definition.getTrigger();
        // No event starts these: a submit trigger is chosen per record by findSubmitFlows, a cron trigger
        // per schedule by findCronFlow, an Api flow is started explicitly by its id, and a Subflow flow runs
        // only from a parent flow.
        if (trigger == null || trigger instanceof SubmitTrigger || trigger instanceof CronTrigger
                || trigger instanceof ApiTrigger || trigger instanceof SubflowTrigger) {
            return false;
        }
        // Derive the trigger type discriminator from the TriggerSource class name
        String triggerTypeName = resolveTriggerTypeName(trigger);
        if (!Objects.equals(triggerTypeName, event.getType())) {
            return false;
        }
        // A change of a record of the flow's model, and no other
        if (!Objects.equals(definition.getModelName(), event.getSourceModel())) {
            return false;
        }
        if (trigger instanceof FieldChangeTrigger fieldTrigger) {
            return matchesFieldChange(fieldTrigger, event);
        }
        // An entity change also matches its change events
        return !(trigger instanceof EntityChangeTrigger change) || matchesChangeEvents(change.events(), event);
    }

    private boolean matchesFieldChange(FieldChangeTrigger trigger, FlowTriggerEvent event) {
        if (trigger.fieldNames() == null || trigger.fieldNames().isEmpty()) {
            return true;
        }
        Map<String, Object> params = event.getParameters();
        Object rawChangedFields = params == null ? null : params.get(PARAM_CHANGED_FIELDS);
        if (rawChangedFields instanceof Collection<?> changedFields) {
            return trigger.fieldNames().stream().anyMatch(changedFields::contains);
        }
        if (rawChangedFields instanceof String changedField) {
            return trigger.fieldNames().contains(changedField);
        }
        return false;
    }

    /**
     * Matches the change event's access type against the trigger's configured
     * {@code events} filter. An empty/unset filter matches every change event;
     * a non-empty filter fires only when the event's access type maps to one of
     * the configured {@link ChangeEvent}s (so a CREATE-only trigger never fires
     * on an UPDATE/DELETE, and no trigger fires on a READ).
     */
    private boolean matchesChangeEvents(List<ChangeEvent> events, FlowTriggerEvent event) {
        if (events == null || events.isEmpty()) {
            return true;
        }
        ChangeEvent actual = resolveChangeEvent(event);
        return actual != null && events.contains(actual);
    }

    private ChangeEvent resolveChangeEvent(FlowTriggerEvent event) {
        Map<String, Object> params = event.getParameters();
        Object raw = params == null ? null : params.get(ChangeLogTriggerMapper.PARAM_ACCESS_TYPE);
        if (raw == null) {
            return null;
        }
        return switch (raw.toString()) {
            case "CREATE" -> ChangeEvent.CREATE;
            case "UPDATE" -> ChangeEvent.UPDATE;
            case "DELETE" -> ChangeEvent.DELETE;
            default -> null; // e.g. READ — entity-change triggers never fire on reads
        };
    }

    private String resolveTriggerTypeName(TriggerSource trigger) {
        // Returns the @JsonSubTypes name value for the concrete type
        return switch (trigger) {
            case EntityChangeTrigger t -> "EntityChange";
            case ApiTrigger t          -> "Api";
            case CronTrigger t         -> "Cron";
            case SubflowTrigger t      -> "Subflow";
            case FieldChangeTrigger t  -> "FieldChange";
            case SubmitTrigger t       -> "Submit";
        };
    }
}
