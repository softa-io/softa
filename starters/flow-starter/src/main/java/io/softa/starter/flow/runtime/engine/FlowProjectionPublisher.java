package io.softa.starter.flow.runtime.engine;

import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

import io.softa.framework.base.context.ContextUtils;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;

/**
 * Publisher that tells the listeners a flow execution state has been saved: every
 * {@link FlowStateChangeListener} after each save, then — when the save moved the instance's status — every
 * {@link FlowStatusChangeListener}.
 * <p>
 * Whether the status moved is decided here, once, from the state alone: the status the action found
 * ({@link FlowExecutionState#getLoadedStatus()}, {@code null} for a new instance) against the one saved. What the
 * action recorded is not read — an action may record more than its decision (the CCs an approval's result sends),
 * and the same action moves the status or not depending on how it turned out. Once announced, the saved status is
 * where the next save of the same action starts from, so a move is announced once.
 * <p>
 * Listeners are auto-discovered by Spring DI. They run past the caller's data permissions: the projections they keep —
 * approval tasks, inbox notices marked read, the outcome written back onto an approved record — follow from an action
 * the engine has already authorized, and the actor rarely holds a grant on those tables.
 */
@Component
public class FlowProjectionPublisher {

    private final List<FlowStateChangeListener> listeners;
    private final List<FlowStatusChangeListener> statusListeners;

    public FlowProjectionPublisher(List<FlowStateChangeListener> listeners,
                                   List<FlowStatusChangeListener> statusListeners) {
        this.listeners = listeners;
        this.statusListeners = statusListeners;
    }

    /**
     * Notify the listeners about a save of {@code state}.
     */
    public void publish(FlowExecutionState state) {
        FlowExecutionStatus from = state.getLoadedStatus();
        boolean moved = !Objects.equals(from, state.getStatus());
        ContextUtils.withoutPermissionChecks(() -> {
            for (FlowStateChangeListener listener : listeners) {
                listener.onStateChanged(state);
            }
            if (moved) {
                for (FlowStatusChangeListener listener : statusListeners) {
                    listener.onStatusChanged(state, from);
                }
            }
        });
        state.setLoadedStatus(state.getStatus());
    }
}
