package io.softa.starter.flow.runtime.monitor;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.runtime.engine.FlowRuntimeEngine;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowWaitToken;
import io.softa.starter.flow.service.FlowInstanceService;

/**
 * Drives the periodic timer recovery sweep: {@link #sweepDueTimers(Instant, int)} resumes WAITING instances with a
 * due timer missed by Pulsar {@code deliverAfter} (e.g. broker restart, long durations).
 */
@Slf4j
@Service
public class FlowMonitorService {

    private final FlowInstanceService instanceService;
    private final FlowRuntimeEngine runtimeEngine;

    public FlowMonitorService(FlowInstanceService instanceService,
                              FlowRuntimeEngine runtimeEngine) {
        this.instanceService = instanceService;
        this.runtimeEngine = runtimeEngine;
    }

    /**
     * Resume up to {@code limit} WAITING instances with a due timer whose {@code next_fire_at} is at or
     * before {@code now}. Used as a fallback when Pulsar {@code deliverAfter} did not deliver
     * (long duration, broker restart, retention loss).
     *
     * @return number of instances successfully resumed
     */
    public int sweepDueTimers(Instant now, int limit) {
        LocalDateTime cutoff = LocalDateTime.ofInstant(now, ZoneId.systemDefault());
        List<FlowInstance> due = instanceService.findDueTimers(cutoff, limit);
        int resumed = 0;
        for (FlowInstance instance : due) {
            Optional<FlowExecutionState> stateOpt = runtimeEngine.getInstance(instance.getInstanceId());
            if (stateOpt.isEmpty()) {
                continue;
            }
            // An instance may hold several timer waits (parallel branches); resume each due one.
            for (FlowWaitToken token : stateOpt.get().dueTimerTokens(cutoff)) {
                try {
                    runtimeEngine.resumeTimer(instance.getInstanceId(), token.getNodeId());
                    resumed++;
                } catch (Exception e) {
                    log.error("Failed to sweep-resume timer for instance {} node {}: {}",
                            instance.getInstanceId(), token.getNodeId(), e.getMessage(), e);
                }
            }
        }
        if (!due.isEmpty()) {
            log.info("Flow timer sweep: scanned={}, resumed={}", due.size(), resumed);
        }
        return resumed;
    }
}
