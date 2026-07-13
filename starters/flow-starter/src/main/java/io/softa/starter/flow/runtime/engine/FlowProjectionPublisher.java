package io.softa.starter.flow.runtime.engine;

import java.util.List;
import org.springframework.stereotype.Component;

import io.softa.starter.flow.runtime.state.FlowExecutionState;

/**
 * Publisher that notifies all registered {@link FlowStateChangeListener}s
 * after a flow execution state has been persisted.
 * <p>
 * Listeners are auto-discovered by Spring DI (any bean implementing
 * {@code FlowStateChangeListener} is automatically included).
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
        for (FlowStateChangeListener listener : listeners) {
            listener.onStateChanged(state);
        }
    }
}

