package io.softa.starter.flow.message.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import io.softa.framework.base.context.Context;

/**
 * Message DTO for waking up a flow instance suspended at a {@code TIMER} node.
 *
 * <p>Published by the timer scheduler when the configured duration, cron schedule,
 * or deadline expression fires. The consumer calls
 * {@link io.softa.starter.flow.runtime.engine.FlowRuntimeEngine#resumeTimer(String, String)}
 * to advance execution past the timer node.</p>
 */
@Data
@NoArgsConstructor
public class FlowTimerMessage {

    /** The suspended flow instance id. */
    private String instanceId;

    /** The TIMER node that triggered the suspension. */
    private String nodeId;

    private Context context;
}
