package io.softa.starter.flow.runtime.nodeconfig;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Config for {@code FlowNodeType.TIMER} nodes.
 * <p>
 * Exactly one of the three duration strategies must be set:
 * <ul>
 *   <li>{@code durationSeconds} — suspend for a fixed number of seconds</li>
 *   <li>{@code cronExpression} — resume at the next cron-schedule firing</li>
 *   <li>{@code deadlineExpression} — AviatorScript expression returning an
 *       {@code Instant} or {@code LocalDateTime}; suspend until that moment</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TimerNodeConfig {

    /** Fixed wait in seconds (mutually exclusive with the other two strategies). */
    private Long durationSeconds;

    /** Cron expression; the instance resumes at the next matching fire time. */
    private String cronExpression;

    /**
     * AviatorScript expression that evaluates to a deadline timestamp.
     * Example: {@code "orderDeadline"} (a variable holding an Instant).
     */
    private String deadlineExpression;
}
