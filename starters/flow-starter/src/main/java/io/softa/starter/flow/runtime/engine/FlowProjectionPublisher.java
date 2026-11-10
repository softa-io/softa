package io.softa.starter.flow.runtime.engine;

import java.util.List;
import org.springframework.stereotype.Component;

import io.softa.framework.base.context.ContextUtils;
import io.softa.starter.flow.runtime.state.FlowExecutionState;

/**
 * Publisher that notifies all registered {@link FlowStateChangeListener}s
 * after a flow execution state has been persisted.
 * <p>
 * Listeners are auto-discovered by Spring DI (any bean implementing
 * {@code FlowStateChangeListener} is automatically included).
 * <p>
 * Listeners run past the caller's data permissions: the projections they keep — approval tasks, inbox
 * notices marked read, the outcome written back onto an approved record — follow from an action the
 * engine has already authorized, and the actor rarely holds a grant on those tables.
 */
@Component
public class FlowProjectionPublisher {

    private final List<FlowStateChangeListener> listeners;

    public FlowProjectionPublisher(List<FlowStateChangeListener> listeners) {
        this.listeners = listeners;
    }

    /**
     * Notify all listeners about a state change.
     */
    public void publish(FlowExecutionState state) {
        ContextUtils.withoutPermissionChecks(() -> {
            for (FlowStateChangeListener listener : listeners) {
                listener.onStateChanged(state);
            }
        });
    }
}

