package io.softa.starter.flow.runtime.engine;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import io.softa.starter.flow.runtime.exception.FlowRuntimeException;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;

/**
 * Explicit legal lifecycle transition graph for {@link FlowExecutionStatus}.
 * <p>
 * Centralizes what was previously implicit across the raw {@code setStatus(...)}
 * sites in the orchestrator and action handlers: every business-driven status
 * change goes through {@link #apply(FlowExecutionState, FlowExecutionStatus)}, so an
 * illegal transition (e.g. {@code COMPLETED → RUNNING}) fails fast in one place
 * instead of silently corrupting an instance.
 * <p>
 * Two transitions are always permitted and not enumerated: a self-transition
 * ({@code target == current}, a no-op) and any state moving to
 * {@link FlowExecutionStatus#FAILED} (error handling can fail an instance from
 * anywhere). Deserialization restores persisted state through the raw setter and
 * is intentionally not validated here.
 */
public final class FlowStatusTransitions {

    private static final Map<FlowExecutionStatus, Set<FlowExecutionStatus>> ALLOWED =
            new EnumMap<>(FlowExecutionStatus.class);

    static {
        // Async-launch: created-but-not-started → running (or force-cancelled before start).
        ALLOWED.put(FlowExecutionStatus.PENDING, EnumSet.of(
                FlowExecutionStatus.RUNNING, FlowExecutionStatus.CANCELLED));
        // Active execution may suspend on any wait (approval / timer / async), complete, or cancel.
        ALLOWED.put(FlowExecutionStatus.RUNNING, EnumSet.of(
                FlowExecutionStatus.WAITING, FlowExecutionStatus.COMPLETED,
                FlowExecutionStatus.CANCELLED));
        // Parked on a wait: resume (approve / timer / async → running), or terminate via
        // reject / return / withdraw / cancel. Parallel branches with different wait reasons
        // share this one status, so a second branch suspending is a legal self-transition.
        ALLOWED.put(FlowExecutionStatus.WAITING, EnumSet.of(
                FlowExecutionStatus.RUNNING, FlowExecutionStatus.REJECTED,
                FlowExecutionStatus.RETURNED, FlowExecutionStatus.WITHDRAWN,
                FlowExecutionStatus.CANCELLED));
        // Returned-to-initiator can only be re-opened by a resubmit (back to waiting).
        ALLOWED.put(FlowExecutionStatus.RETURNED, EnumSet.of(
                FlowExecutionStatus.WAITING, FlowExecutionStatus.CANCELLED));
        // REJECTED / WITHDRAWN / CANCELLED / COMPLETED / FAILED are terminal:
        // no outgoing business transition (a re-opened approval resubmits from RETURNED only).
    }

    private FlowStatusTransitions() {
    }

    /**
     * @return {@code true} if moving from {@code from} to {@code to} is a legal lifecycle
     *         transition (self-transitions and any → FAILED are always legal).
     */
    public static boolean isAllowed(FlowExecutionStatus from, FlowExecutionStatus to) {
        if (from == null || to == null || from == to || to == FlowExecutionStatus.FAILED) {
            return true;
        }
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    /**
     * @throws FlowRuntimeException if the transition is not legal per {@link #isAllowed}.
     */
    public static void validate(FlowExecutionStatus from, FlowExecutionStatus to) {
        if (!isAllowed(from, to)) {
            throw new FlowRuntimeException("Illegal flow status transition: " + from + " -> " + to);
        }
    }

    /**
     * Guarded status mutation: validates {@code current → target} against the legal
     * lifecycle graph before applying it. Business-logic status changes (orchestrator,
     * action handlers) must use this rather than the raw {@code setStatus} setter on
     * {@link FlowExecutionState}, which is reserved for deserialization / building.
     *
     * @throws FlowRuntimeException on an illegal transition
     */
    public static void apply(FlowExecutionState state, FlowExecutionStatus target) {
        validate(state.getStatus(), target);
        state.setStatus(target);
    }
}
