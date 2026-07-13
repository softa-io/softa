package io.softa.starter.flow.runtime.trigger;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.starter.flow.entity.FlowEvent;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.message.ChangeLogTriggerMapper;
import io.softa.starter.flow.message.FlowEventProducer;
import io.softa.starter.flow.message.dto.FlowEventMessage;
import io.softa.starter.flow.runtime.api.FlowOnchangeRequest;
import io.softa.starter.flow.runtime.api.FlowStartRequest;
import io.softa.starter.flow.runtime.api.FlowValidationRequest;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.engine.FlowRuntimeEngine;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.service.FlowEventService;
import io.softa.starter.flow.service.FlowInstanceService;

/**
 * High-level automation service that receives external {@link FlowTriggerEvent}s,
 * resolves matching flows via {@link DefaultFlowTriggerRegistry}, and starts each one
 * through the {@link FlowRuntimeEngine}.
 * <p>
 * When a {@link FlowEventService} is available, each trigger fire is
 * recorded as a persistent event log entry for auditing.
 * </p>
 */
@Slf4j
@Service
public class FlowAutomationService {

    private final DefaultFlowTriggerRegistry triggerRegistry;
    private final FlowRuntimeEngine runtimeEngine;

    @Autowired(required = false)
    private FlowEventService eventService;

    @Autowired(required = false)
    private FlowEventProducer flowEventProducer;

    @Autowired(required = false)
    private FlowInstanceService instanceService;

    public FlowAutomationService(DefaultFlowTriggerRegistry triggerRegistry,
                                 FlowRuntimeEngine runtimeEngine) {
        this.triggerRegistry = triggerRegistry;
        this.runtimeEngine = runtimeEngine;
    }

    /**
     * Publish the event to Pulsar for asynchronous fan-out via {@code mq.topics.flow-event}.
     * <p>
     * Preferred for high-cardinality producers (e.g. ChangeLog) so that flow matching
     * and execution happen in the flow-event consumer's virtual-thread pool instead of
     * blocking the upstream consumer thread. Falls back to in-thread {@link #fireAsyncOnly}
     * when the producer bean is not available (topic not configured).
     * </p>
     */
    public void fireAsync(FlowTriggerEvent event) {
        if (flowEventProducer == null) {
            fireAsyncOnly(event);
            return;
        }
        FlowEventMessage message = new FlowEventMessage();
        message.setTriggerType(event.getType());
        message.setSourceModel(event.getSourceModel());
        message.setSourceRowId(event.getSourceRowId());
        message.setActorId(event.getActorId());
        message.setParameters(event.getParameters());
        message.setEventTime(LocalDateTime.now());
        message.setContext(ContextHolder.cloneContext());
        flowEventProducer.sendFlowEvent(message);
    }

    /**
     * Fire a trigger event. All matching flows will be started synchronously.
     */
    public List<FlowTriggerResult> fire(FlowTriggerEvent event) {
        return dispatch(event, null, definition -> startFlow(definition, event), "fire");
    }

    /**
     * Fire a trigger event but only execute flows with {@code sync=true}. A matching flow with
     * {@code rollbackOnFail=true} is started with exception propagation so the caller's transaction
     * can roll back.
     */
    public void fireSyncOnly(FlowTriggerEvent event) {
        dispatch(event,
                definition -> Boolean.TRUE.equals(definition.getSync()),
                definition -> Boolean.TRUE.equals(definition.getRollbackOnFail())
                        ? startFlowWithPropagation(definition, event)
                        : startFlow(definition, event),
                "fireSyncOnly");
    }

    /**
     * Fire a trigger event but only execute flows with {@code sync=false} (async flows).
     */
    public List<FlowTriggerResult> fireAsyncOnly(FlowTriggerEvent event) {
        return dispatch(event,
                definition -> !Boolean.TRUE.equals(definition.getSync()),
                definition -> startFlow(definition, event),
                "fireAsyncOnly");
    }

    /**
     * Fire a trigger event and only run flows matching the given scenario.
     */
    public List<FlowTriggerResult> fireForScenario(FlowTriggerEvent event, FlowScenario scenario) {
        return dispatch(event,
                definition -> scenario.equals(definition.getScenario()),
                definition -> startFlow(definition, event),
                "fireForScenario");
    }

    /**
     * Shared trigger-dispatch skeleton: resolve matching flows, run each {@code accept}-ed flow
     * through {@code starter}, and record an event-log entry per fire. {@code accept} may be null
     * (accept all matching flows).
     */
    private List<FlowTriggerResult> dispatch(FlowTriggerEvent event,
                                             Predicate<CompiledFlowDefinition> accept,
                                             Function<CompiledFlowDefinition, FlowTriggerResult> starter,
                                             String fireMethod) {
        List<CompiledFlowDefinition> matchingFlows = triggerRegistry.findMatchingFlows(event);
        if (matchingFlows.isEmpty()) {
            log.debug("No flows matched trigger event ({}): type={}, sourceModel={}",
                    fireMethod, event.getType(), event.getSourceModel());
            return List.of();
        }
        List<FlowTriggerResult> results = new ArrayList<>();
        for (CompiledFlowDefinition definition : matchingFlows) {
            if (accept != null && !accept.test(definition)) {
                continue;
            }
            FlowTriggerResult result = starter.apply(definition);
            results.add(result);
            // Transient evaluations (Validation / Compute) leave zero DB footprint — a
            // high-frequency onchange must not accumulate one event-log row per keystroke.
            if (!isTransientScenario(definition)) {
                recordEvent(event, definition, result, fireMethod);
            }
        }
        return results;
    }

    /**
     * Validation / Compute flows are stateless: they evaluate transiently (no instance row,
     * no trace, no event log). A missing scenario is treated as Process, conservatively.
     */
    private static boolean isTransientScenario(CompiledFlowDefinition definition) {
        return definition.getScenario() != null && !FlowScenario.PROCESS.equals(definition.getScenario());
    }

    /**
     * Evaluate all {@link FlowScenario#COMPUTE} flows for the given onchange request.
     * Merges currentData + fieldChanges, fires matching flows, and returns the variables diff.
     */
    public Map<String, Object> evaluateOnchange(FlowOnchangeRequest request) {
        Map<String, Object> variables = new LinkedHashMap<>();
        if (request.getCurrentData() != null) {
            variables.putAll(request.getCurrentData());
        }
        if (request.getFieldChanges() != null) {
            variables.putAll(request.getFieldChanges());
            variables.put(DefaultFlowTriggerRegistry.PARAM_CHANGED_FIELDS,
                    List.copyOf(request.getFieldChanges().keySet()));
        }
        Map<String, Object> before = Map.copyOf(variables);

        FlowTriggerEvent event = FlowTriggerEvent.builder()
                .type("FieldChange")
                .sourceModel(request.getSourceModel())
                .parameters(variables)
                .actorId(request.getActorId())
                .build();

        List<FlowTriggerResult> results = fireForScenario(event, FlowScenario.COMPUTE);

        Map<String, Object> diff = new LinkedHashMap<>();
        for (FlowTriggerResult result : results) {
            if (result.isSuccess() && result.getState() != null && result.getState().getVariables() != null) {
                for (Map.Entry<String, Object> entry : result.getState().getVariables().entrySet()) {
                    String key = entry.getKey();
                    if (key.startsWith("_")) {
                        continue;
                    }
                    Object newValue = entry.getValue();
                    Object oldValue = before.get(key);
                    if (!Objects.equals(oldValue, newValue)) {
                        diff.put(key, newValue);
                    }
                }
            }
        }

        for (FlowTriggerResult result : results) {
            if (result.isSuccess() && result.getState() != null && result.getState().getReturnData() != null) {
                diff.put("_returnData", result.getState().getReturnData());
            }
        }

        return diff;
    }

    /**
     * Evaluate all {@link FlowScenario#VALIDATION} flows bound to the given model transiently
     * and return each flow's declared outputs (plus {@code _returnData} when present), keyed
     * by flow code. Fail-closed: a validation flow that cannot run propagates its exception
     * instead of being silently skipped — a validation that did not execute must not read as
     * "passed".
     */
    public Map<String, Map<String, Object>> evaluateValidation(FlowValidationRequest request) {
        Map<String, Object> variables = new LinkedHashMap<>();
        if (request.getData() != null) {
            variables.putAll(request.getData());
        }
        if (request.getChangeType() != null && !request.getChangeType().isBlank()) {
            variables.put(ChangeLogTriggerMapper.PARAM_ACCESS_TYPE, request.getChangeType());
        }

        FlowTriggerEvent event = FlowTriggerEvent.builder()
                .type("EntityChange")
                .sourceModel(request.getSourceModel())
                .parameters(variables)
                .actorId(request.getActorId())
                .build();

        Map<String, Map<String, Object>> outputsByFlow = new LinkedHashMap<>();
        for (CompiledFlowDefinition definition : triggerRegistry.findMatchingFlows(event)) {
            if (!FlowScenario.VALIDATION.equals(definition.getScenario())) {
                continue;
            }
            FlowTriggerResult result = startFlowWithPropagation(definition, event);
            outputsByFlow.put(definition.getFlowCode(), projectDeclaredOutputs(definition, result.getState()));
        }
        return outputsByFlow;
    }

    /** The subset of final variables a Validation/Compute flow promised via declaredOutputs. */
    private static Map<String, Object> projectDeclaredOutputs(CompiledFlowDefinition definition,
                                                              FlowExecutionState state) {
        Map<String, Object> outputs = new LinkedHashMap<>();
        Map<String, Object> variables = state == null || state.getVariables() == null
                ? Map.of() : state.getVariables();
        if (definition.getDeclaredOutputs() != null) {
            for (String key : definition.getDeclaredOutputs()) {
                if (variables.containsKey(key)) {
                    outputs.put(key, variables.get(key));
                }
            }
        }
        if (state != null && state.getReturnData() != null) {
            outputs.put("_returnData", state.getReturnData());
        }
        return outputs;
    }

    private FlowTriggerResult startFlow(CompiledFlowDefinition definition, FlowTriggerEvent event) {
        try {
            FlowExecutionState state = doStartFlow(definition, event);
            return FlowTriggerResult.builder()
                    .flowCode(definition.getFlowCode())
                    .success(true)
                    .state(state)
                    .build();
        } catch (Exception e) {
            log.error("Failed to start flow {} from trigger: {}", definition.getFlowCode(), e.getMessage(), e);
            return FlowTriggerResult.builder()
                    .flowCode(definition.getFlowCode())
                    .success(false)
                    .error(e.getMessage())
                    .build();
        }
    }

    /**
     * Start flow and let exceptions propagate (for rollbackOnFail semantics).
     */
    private FlowTriggerResult startFlowWithPropagation(CompiledFlowDefinition definition, FlowTriggerEvent event) {
        FlowExecutionState state = doStartFlow(definition, event);
        return FlowTriggerResult.builder()
                .flowCode(definition.getFlowCode())
                .success(true)
                .state(state)
                .build();
    }

    private FlowExecutionState doStartFlow(CompiledFlowDefinition definition, FlowTriggerEvent event) {
        // Stateless scenarios evaluate transiently — no instance exists to dedup against.
        if (isTransientScenario(definition)) {
            return runtimeEngine.evaluate(buildStartRequest(definition, event));
        }
        // Start-dedup: a change-log redelivery must not open a SECOND active instance
        // for the same business row + flow. If one is already active, return it idempotently.
        FlowExecutionState active = findActiveInstanceState(
                definition.getFlowCode(), event.getSourceModel(), event.getSourceRowId());
        if (active != null) {
            return active;
        }
        return runtimeEngine.start(buildStartRequest(definition, event));
    }

    private static FlowStartRequest buildStartRequest(CompiledFlowDefinition definition, FlowTriggerEvent event) {
        Map<String, Object> variables = new LinkedHashMap<>();
        if (event.getParameters() != null) {
            variables.putAll(event.getParameters());
        }
        // Inject trigger metadata into variables
        variables.put("_triggerType", event.getType());
        variables.put("_sourceModel", event.getSourceModel());
        variables.put("_sourceRowId", event.getSourceRowId());

        FlowStartRequest request = new FlowStartRequest();
        request.setBundleId(definition.getBundleId());
        request.setInitiatorId(event.getActorId());
        request.setModelName(event.getSourceModel());
        request.setRowId(event.getSourceRowId());
        request.setVariables(variables);
        return request;
    }

    /**
     * The currently-active instance for a business row + flow, or {@code null}. "Active" = not in a
     * terminal status, so a re-submitted row (whose prior instance is terminal) can legitimately start
     * a new one. Returns null when no {@link FlowInstanceService} is wired or the event carries no
     * business row (modelName / rowId).
     */
    FlowExecutionState findActiveInstanceState(String flowCode, String model, String rowId) {
        if (instanceService == null || model == null || model.isBlank() || rowId == null || rowId.isBlank()) {
            return null;
        }
        return instanceService.findByModelNameAndRowId(model, rowId).stream()
                .filter(i -> flowCode != null && flowCode.equals(i.getFlowCode()))
                .filter(i -> i.getStatus() != null && !i.getStatus().isTerminal())
                .findFirst()
                .flatMap(i -> runtimeEngine.getInstance(i.getInstanceId()))
                .orElse(null);
    }

    // ==================== Event Logging ====================

    private void recordEvent(FlowTriggerEvent event, CompiledFlowDefinition definition,
                             FlowTriggerResult result, String fireMethod) {
        if (eventService == null) {
            return;
        }
        try {
            FlowEvent logEntry = new FlowEvent();
            logEntry.setTriggerType(event.getType());
            logEntry.setSourceModel(event.getSourceModel());
            logEntry.setSourceRowId(event.getSourceRowId());
            logEntry.setActorId(event.getActorId());
            logEntry.setFlowCode(definition.getFlowCode());
            logEntry.setFlowRevision(definition.getRevision());
            logEntry.setInstanceId(result.getState() != null ? result.getState().getInstanceId() : null);
            logEntry.setSuccess(result.isSuccess());
            logEntry.setErrorMessage(result.getError());
            logEntry.setFireMethod(fireMethod);
            logEntry.setEventTime(LocalDateTime.now());
            logEntry.setParameters(JsonUtils.objectToString(event.getParameters()));
            eventService.recordEvent(logEntry);
        } catch (Exception e) {
            log.warn("Failed to record trigger event log: {}", e.getMessage());
        }
    }

}
