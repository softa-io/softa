package io.softa.starter.flow.runtime.trigger;

import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.compute.ComputeUtils;
import io.softa.starter.flow.design.trigger.*;
import io.softa.starter.flow.message.ChangeLogTriggerMapper;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.FlowBundleRegistry;

/**
 * Default trigger registry that scans all registered bundles and matches
 * by trigger type, source model, and optional condition expression.
 */
@Component
public class DefaultFlowTriggerRegistry {

    static final String PARAM_CHANGED_FIELDS = "_changedFields";

    private final FlowBundleRegistry bundleRegistry;

    public DefaultFlowTriggerRegistry(FlowBundleRegistry bundleRegistry) {
        this.bundleRegistry = bundleRegistry;
    }

    public List<CompiledFlowDefinition> findMatchingFlows(FlowTriggerEvent event) {
        // The bundle cache is warmed cross-tenant for performance, so isolation is enforced
        // here at match time: an event may only fire flows of its own tenant (plus
        // platform-shared flows). Requires the event's tenant to be in context (the consumer
        // restores it from the message).
        Long currentTenant = ContextHolder.existContext() ? ContextHolder.getContext().getTenantId() : null;
        List<CompiledFlowDefinition> matches = new ArrayList<>();
        for (CompiledFlowDefinition definition : bundleRegistry.list()) {
            if (!tenantMatches(definition, currentTenant)) {
                continue;
            }
            if (matches(definition, event)) {
                matches.add(definition);
            }
        }
        return matches;
    }

    /**
     * Match-time tenant isolation: a platform-shared flow ({@code tenantId == 0}) applies to
     * every tenant; otherwise the flow's tenant must equal the event's current tenant.
     */
    private boolean tenantMatches(CompiledFlowDefinition definition, Long currentTenant) {
        Long flowTenant = definition.getTenantId();
        if (flowTenant != null && flowTenant == 0L) {
            return true;
        }
        return Objects.equals(flowTenant, currentTenant);
    }

    private boolean matches(CompiledFlowDefinition definition, FlowTriggerEvent event) {
        TriggerSource trigger = definition.getTrigger();
        if (trigger == null) {
            return false;
        }
        // Derive the trigger type discriminator from the TriggerSource class name
        String triggerTypeName = resolveTriggerTypeName(trigger);
        if (!Objects.equals(triggerTypeName, event.getType())) {
            return false;
        }
        if (trigger instanceof FieldChangeTrigger fieldTrigger && !matchesFieldChange(fieldTrigger, event)) {
            return false;
        }
        // For entity-related triggers, match source model, change events, and evaluate condition
        if (trigger instanceof EntityChangeTrigger(String modelName, List<ChangeEvent> events, String conditionExpression)) {
            if (StringUtils.hasText(modelName)
                    && !Objects.equals(modelName, event.getSourceModel())) {
                return false;
            }
            if (!matchesChangeEvents(events, event)) {
                return false;
            }
            if (StringUtils.hasText(conditionExpression)) {
                try {
                    return ComputeUtils.executeBoolean(
                            conditionExpression, event.getParameters());
                } catch (Exception e) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean matchesFieldChange(FieldChangeTrigger trigger, FlowTriggerEvent event) {
        if (StringUtils.hasText(trigger.modelName())
                && !Objects.equals(trigger.modelName(), event.getSourceModel())) {
            return false;
        }
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
        };
    }
}
