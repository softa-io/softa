package io.softa.starter.flow.runtime.trigger;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.softa.framework.base.context.ContextUtils;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.flow.entity.FlowEvent;
import io.softa.starter.flow.runtime.api.FlowStartRequest;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.context.RecordVariables;
import io.softa.starter.flow.runtime.engine.FlowRuntimeEngine;
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

    /** Trigger type of {@code SubmitTrigger}: started per record, never by an event. */
    public static final String SUBMIT_TRIGGER_TYPE = "Submit";

    /** Trigger type of {@code CronTrigger}: started on its own schedule, never by an event. */
    public static final String CRON_TRIGGER_TYPE = "Cron";

    private final DefaultFlowTriggerRegistry triggerRegistry;
    private final FlowRuntimeEngine runtimeEngine;

    @Autowired(required = false)
    private FlowEventService eventService;

    @Autowired(required = false)
    private FlowInstanceService instanceService;

    /** Reads what an edge's rule reads up to {@link RecordVariables#MAX_HOPS} relations on from the changed record. */
    @Autowired(required = false)
    private ModelService<?> modelService;

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
     * Start the Cron-triggered flow revision a schedule fired for, in the current tenant — the one its
     * cron job runs in — and record the fire like any other. Starts nothing once the revision is no longer
     * its flow's active one (see {@link DefaultFlowTriggerRegistry#findCronFlow}).
     *
     * @param bundleId   the revision the cron job was scheduled from
     * @param parameters the flow's initial variables
     */
    public void fireCron(Long bundleId, Map<String, Object> parameters) {
        FlowTriggerEvent event = FlowTriggerEvent.builder()
                .type(CRON_TRIGGER_TYPE)
                .parameters(parameters)
                .build();
        List<TriggerMatch> scheduledFlow = triggerRegistry.findCronFlow(bundleId).map(TriggerMatch::holding).stream()
                .toList();
        dispatch(event, scheduledFlow, null, match -> start(match, event), "fireCron");
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
    public void fireAsyncOnly(FlowTriggerEvent event) {
        dispatch(event, triggerRegistry.findMatches(event),
                definition -> !Boolean.TRUE.equals(definition.getSync()),
                match -> start(match, event),
                "fireAsyncOnly");
    }

    /**
     * Shared trigger-dispatch skeleton: run each {@code accept}-ed flow of those the event matched
     * through {@code starter}, and record an event-log entry per fire — a failed one too, a trigger
     * condition that could not be evaluated among them. {@code accept} may be null (accept all
     * matching flows).
     */
    private void dispatch(FlowTriggerEvent event,
                          List<TriggerMatch> matches,
                          Predicate<CompiledFlowDefinition> accept,
                          Function<TriggerMatch, Start> starter,
                          String fireMethod) {
        if (matches.isEmpty()) {
            log.debug("No flows matched trigger event ({}): type={}, sourceModel={}",
                    fireMethod, event.getType(), event.getSourceModel());
            return;
        }
        for (TriggerMatch match : matches) {
            CompiledFlowDefinition definition = match.definition();
            if (accept != null && !accept.test(definition)) {
                continue;
            }
            recordEvent(event, definition, starter.apply(match), fireMethod);
        }
    }

    /** What starting one matched flow came to, for its event-log entry: the run, or why there is none. */
    private record Start(String instanceId, boolean success, String error) {

        static Start of(FlowExecutionState state) {
            return new Start(state == null ? null : state.getInstanceId(), true, null);
        }

        static Start failed(String error) {
            return new Start(null, false, error);
        }
    }

    /**
     * Start the flow an event matched; a trigger condition that could not be evaluated fails it without a run.
     */
    private Start start(TriggerMatch match, FlowTriggerEvent event) {
        if (!match.holds()) {
            log.error("Flow {} not started from trigger: {}", match.definition().getFlowId(), match.error());
            return Start.failed(match.error());
        }
        return startFlow(match.definition(), event);
    }

    private Start startFlow(CompiledFlowDefinition definition, FlowTriggerEvent event) {
        try {
            return Start.of(doStartFlow(definition, event));
        } catch (Exception e) {
            log.error("Failed to start flow {} from trigger: {}", definition.getFlowId(), e.getMessage(), e);
            return Start.failed(e.getMessage());
        }
    }

    /**
     * Start flow and let exceptions propagate (for rollbackOnFail semantics): a trigger condition that could not be
     * evaluated throws too.
     */
    private Start startFlowWithPropagation(TriggerMatch match, FlowTriggerEvent event) {
        if (!match.holds()) {
            throw new FlowRuntimeException(match.error());
        }
        return Start.of(doStartFlow(match.definition(), event));
    }

    private FlowExecutionState doStartFlow(CompiledFlowDefinition definition, FlowTriggerEvent event) {
        // An event starts a flow at most once: a redelivered change log finds the run it already
        // started and returns it. Distinct events each start their own run, even on the same row.
        FlowExecutionState started = findRunOfEvent(definition.getFlowId(), event.getEventId());
        if (started != null) {
            return started;
        }
        return runtimeEngine.start(buildStartRequest(definition, event));
    }

    private FlowStartRequest buildStartRequest(CompiledFlowDefinition definition, FlowTriggerEvent event) {
        FlowStartRequest request = new FlowStartRequest();
        request.setBundleId(definition.getBundleId());
        request.setInitiatorId(event.getActorId());
        request.setModelName(event.getSourceModel());
        request.setRowId(event.getSourceRowId());
        request.setTriggerEventId(event.getEventId());
        request.setVariables(startVariables(definition, event));
        return request;
    }

    /**
     * What a run the event starts begins with: the event's own variables and, of the paths its edges' rules read
     * through a relation of the changed record, those read now from the related records. An update brings only what
     * it changed, so a relation it left alone is read from the stored record.
     */
    private Map<String, Object> startVariables(CompiledFlowDefinition definition, FlowTriggerEvent event) {
        Map<String, Object> variables = event.startVariables();
        Set<String> paths = definition.rulePaths();
        if (!paths.isEmpty() && modelService != null && event.getSourceModel() != null) {
            variables.putAll(RecordVariables.readPaths(modelService, event.getSourceModel(), event.getSourceRowId(),
                    event.getParameters() == null ? Map.of() : event.getParameters(), paths));
        }
        return variables;
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

    private void recordEvent(FlowTriggerEvent event, CompiledFlowDefinition definition, Start start,
                             String fireMethod) {
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
            logEntry.setInstanceId(start.instanceId());
            logEntry.setSuccess(start.success());
            logEntry.setErrorMessage(start.error());
            logEntry.setFireMethod(fireMethod);
            logEntry.setEventTime(LocalDateTime.now());
            logEntry.setParameters(JsonUtils.objectToString(event.getParameters()));
            eventService.recordEvent(logEntry);
        } catch (Exception e) {
            log.warn("Failed to record trigger event log: {}", e.getMessage());
        }
    }

}
