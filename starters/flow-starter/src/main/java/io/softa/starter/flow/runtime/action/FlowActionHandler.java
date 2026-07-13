package io.softa.starter.flow.runtime.action;

import io.softa.starter.flow.runtime.state.FlowExecutionState;

/**
 * Unified interface for all flow action handlers.
 * @param <R> the request type
 */
public interface FlowActionHandler<R> {
    FlowExecutionState handle(R request);
}
