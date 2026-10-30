package io.softa.starter.flow.runtime.trigger;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.context.ContextUtils;
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
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.exception.FlowRuntimeException;
import io.softa.starter.flow.runtime.trigger.DefaultFlowTriggerRegistry.TriggerMatch;
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

    /** Trigger type of {@code SubmitTrigger}: started per record, never fired as an event. */
    public static final String SUBMIT_TRIGGER_TYPE = "Submit";

    /** Trigger type of {@code CronTrigger}: started on its own schedule, never fired as an event. */
    public static final String CRON_TRIGGER_TYPE = "Cron";

    /**
     * Trigger types no event starts — each names its own way in — with the refusal a fired event of
     * that type gets.
     */
    private static final Map<String, String> NON_EVENT_TRIGGER_TYPES = Map.of(
            SUBMIT_TRIGGER_TYPE, "Submit triggers start only through record submission, not as a trigger event",
            CRON_TRIGGER_TYPE, "Cron triggers start only on their own schedule, not as a trigger event",
            "Api", "Api-triggered flows are started explicitly by their id (POST /flow/instances/start), "
                    + "not as a trigger event",
            "Subflow", "Subflow-triggered flows run only from a parent flow's Subflow node, not as a trigger event");

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
     * Whether any flow in the current context watches entity changes of {@code model}; change logs
     * of other models need not become trigger events at all.
     */
    public boolean hasEntityChangeTrigger(String model) {
        return triggerRegistry.hasEntityChangeTrigger(model);
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
        message.setEventId(event.getEventId());
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
     * <p>
     * Refuses the trigger types no event starts. A submit-triggered flow is chosen for one record and
     * started by {@code FlowLaunchService.submitRecord}, which runs the business checks and the
     * record's callbacks — firing it from here would start the approval without either. A Cron flow
     * starts on its own schedule, through {@link #fireCron}; an Api flow is started explicitly by its
     * id, and a Subflow flow only from a parent flow.
     */
    public List<FlowTriggerResult> fire(FlowTriggerEvent event) {
        String refusal = NON_EVENT_TRIGGER_TYPES.get(event.getType());
        if (refusal != null) {
            throw new FlowActionValidationException(refusal);
        }
        return dispatch(event, triggerRegistry.findMatches(event), null, match -> start(match, event), "fire");
    }

    /**
     * Start the Cron-triggered flow revision a schedule fired for, in the current tenant — the one its
     * cron job runs in — and record the fire like any other. Starts nothing once the revision is no longer
     * its flow's active one (see {@link DefaultFlowTriggerRegistry#findCronFlow}).
     *
     * @param bundleId   the revision the cron job was scheduled from
     * @param parameters the flow's initial variables
     * @return the start, or nothing when the revision no longer runs on this schedule
     */
    public List<FlowTriggerResult> fireCron(Long bundleId, Map<String, Object> parameters) {
        FlowTriggerEvent event = FlowTriggerEvent.builder()
                .type(CRON_TRIGGER_TYPE)
                .parameters(parameters)
                .build();
        List<TriggerMatch> scheduledFlow = triggerRegistry.findCronFlow(bundleId).map(TriggerMatch::holding).stream()
                .toList();
        return dispatch(event, scheduledFlow, null, match -> start(match, event), "fireCron");
    }

    /**
     * Fire a trigger event but only execute flows with {@code sync=true}. A matching flow with
     * {@code rollbackOnFail=true} is started with exception propagation so the caller's transaction
     * can roll back — and so is a trigger condition of such a flow that could not be evaluated.
     */
    public void fireSyncOnly(FlowTriggerEvent event) {
        dispatch(event, triggerRegistry.findMatches(event),
                definition -> Boolean.TRUE.equals(definition.getSync()),
                match -> Boolean.TRUE.equals(match.definition().getRollbackOnFail())
                        ? startFlowWithPropagation(match, event)
                        : start(match, event),
                "fireSyncOnly");
    }

    /**
     * Fire a trigger event but only execute flows with {@code sync=false} (async flows).
     */
    public List<FlowTriggerResult> fireAsyncOnly(FlowTriggerEvent event) {
        return dispatch(event, triggerRegistry.findMatches(event),
                definition -> !Boolean.TRUE.equals(definition.getSync()),
                match -> start(match, event),
                "fireAsyncOnly");
    }

    /**
     * Fire a trigger event and only run flows matching the given scenario.
     */
    public List<FlowTriggerResult> fireForScenario(FlowTriggerEvent event, FlowScenario scenario) {
        return dispatch(event, triggerRegistry.findMatches(event),
                definition -> scenario.equals(definition.getScenario()),
                match -> start(match, event),
                "fireForScenario");
    }

    /**
     * Shared trigger-dispatch skeleton: run each {@code accept}-ed flow of those the event matched
     * through {@code starter}, and record an event-log entry per fire — a failed one too, a trigger
     * condition that could not be evaluated among them. {@code accept} may be null (accept all
     * matching flows).
     */
    private List<FlowTriggerResult> dispatch(FlowTriggerEvent event,
                                             List<TriggerMatch> matches,
                                             Predicate<CompiledFlowDefinition> accept,
                                             Function<TriggerMatch, FlowTriggerResult> starter,
                                             String fireMethod) {
        if (matches.isEmpty()) {
            log.debug("No flows matched trigger event ({}): type={}, sourceModel={}",
                    fireMethod, event.getType(), event.getSourceModel());
            return List.of();
        }
        List<FlowTriggerResult> results = new ArrayList<>();
        for (TriggerMatch match : matches) {
            CompiledFlowDefinition definition = match.definition();
            if (accept != null && !accept.test(definition)) {
                continue;
            }
            FlowTriggerResult result = starter.apply(match);
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
     * no trace, no event log). A missing scenario is treated as persisted, conservatively.
     */
    private static boolean isTransientScenario(CompiledFlowDefinition definition) {
        return definition.getScenario() != null && definition.getScenario().isTransient();
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
     * by flow ID. Fail-closed: a validation flow that cannot run propagates its exception
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
        for (TriggerMatch match : triggerRegistry.findMatches(event)) {
            CompiledFlowDefinition definition = match.definition();
            if (!FlowScenario.VALIDATION.equals(definition.getScenario())) {
                continue;
            }
            FlowTriggerResult result = startFlowWithPropagation(match, event);
            outputsByFlow.put(definition.getFlowId(), projectDeclaredOutputs(definition, result.getState()));
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

    /**
     * Start the flow an event matched; a trigger condition that could not be evaluated fails it without a run.
     */
    private FlowTriggerResult start(TriggerMatch match, FlowTriggerEvent event) {
        if (!match.holds()) {
            log.error("Flow {} not started from trigger: {}", match.definition().getFlowId(), match.error());
            return FlowTriggerResult.builder()
                    .flowId(match.definition().getFlowId())
                    .success(false)
                    .error(match.error())
                    .build();
        }
        return startFlow(match.definition(), event);
    }

    private FlowTriggerResult startFlow(CompiledFlowDefinition definition, FlowTriggerEvent event) {
        try {
            FlowExecutionState state = doStartFlow(definition, event);
            return FlowTriggerResult.builder()
                    .flowId(definition.getFlowId())
                    .success(true)
                    .state(state)
                    .build();
        } catch (Exception e) {
            log.error("Failed to start flow {} from trigger: {}", definition.getFlowId(), e.getMessage(), e);
            return FlowTriggerResult.builder()
                    .flowId(definition.getFlowId())
                    .success(false)
                    .error(e.getMessage())
                    .build();
        }
    }

    /**
     * Start flow and let exceptions propagate (for rollbackOnFail semantics, and for a validation, which
     * must not read as passed when it did not run): a trigger condition that could not be evaluated
     * throws too.
     */
    private FlowTriggerResult startFlowWithPropagation(TriggerMatch match, FlowTriggerEvent event) {
        if (!match.holds()) {
            throw new FlowRuntimeException(match.error());
        }
        CompiledFlowDefinition definition = match.definition();
        FlowExecutionState state = doStartFlow(definition, event);
        return FlowTriggerResult.builder()
                .flowId(definition.getFlowId())
                .success(true)
                .state(state)
                .build();
    }

    private FlowExecutionState doStartFlow(CompiledFlowDefinition definition, FlowTriggerEvent event) {
        // Stateless scenarios evaluate transiently — no instance exists to dedup against.
        if (isTransientScenario(definition)) {
            return runtimeEngine.evaluate(buildStartRequest(definition, event));
        }
        // An event starts a flow at most once: a redelivered change log finds the run it already
        // started and returns it. Distinct events each start their own run, even on the same row.
        FlowExecutionState started = findRunOfEvent(definition.getFlowId(), event.getEventId());
        if (started != null) {
            return started;
        }
        return runtimeEngine.start(buildStartRequest(definition, event));
    }

    private static FlowStartRequest buildStartRequest(CompiledFlowDefinition definition, FlowTriggerEvent event) {
        FlowStartRequest request = new FlowStartRequest();
        request.setBundleId(definition.getBundleId());
        request.setInitiatorId(event.getActorId());
        request.setModelName(event.getSourceModel());
        request.setRowId(event.getSourceRowId());
        request.setTriggerEventId(event.getEventId());
        request.setVariables(event.startVariables());
        return request;
    }

    /**
     * The run {@code eventId} already started for the flow, or {@code null} — also when the event
     * carries no identity (it is then not deduplicated) or no {@link FlowInstanceService} is wired.
     * Read past the caller's data permissions: whether the event already ran must not depend on whose
     * write fired it. Two deliveries racing each other both pass this read; the unique index on the
     * event id then refuses the second instance, though not the side effects its nodes already had.
     */
    FlowExecutionState findRunOfEvent(String flowId, String eventId) {
        if (instanceService == null || flowId == null || eventId == null || eventId.isBlank()) {
            return null;
        }
        return ContextUtils.withoutPermissionChecks(() -> instanceService.findByTriggerEvent(flowId, eventId))
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
            logEntry.setFlowId(definition.getFlowId());
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
