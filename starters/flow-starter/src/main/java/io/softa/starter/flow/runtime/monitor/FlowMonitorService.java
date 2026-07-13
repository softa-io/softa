package io.softa.starter.flow.runtime.monitor;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.runtime.engine.FlowRuntimeEngine;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.runtime.state.FlowWaitToken;
import io.softa.starter.flow.service.FlowInstanceService;

/**
 * Aggregates flow runtime health signals and drives periodic recovery sweeps.
 * <ul>
 *   <li>{@link #snapshot()} — on-demand view of running-instance distribution and overdue counts</li>
 *   <li>{@link #sweepDueTimers(Instant, int)} — resumes WAITING instances with a due timer missed by Pulsar
 *       {@code deliverAfter} (e.g. broker restart, long durations)</li>
 *   <li>{@link #sweepStuckInstances(Instant, int)} — logs non-terminal instances whose last
 *       update time predates the threshold, for operator attention</li>
 * </ul>
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
     * Capture the current runtime snapshot: per-status instance counts plus overdue-timer count.
     */
    public FlowHealthSnapshot snapshot() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (FlowExecutionStatus status : FlowExecutionStatus.values()) {
            // key by the wire value (@JsonValue), consistent with every other flow enum payload
            counts.put(status.getType(), instanceService.countByStatus(status));
        }
        long overdueTimers = instanceService.countDueTimers(LocalDateTime.now());
        return FlowHealthSnapshot.builder()
                .capturedAt(Instant.now())
                .instanceCountByStatus(counts)
                .overdueTimerCount(overdueTimers)
                .build();
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

    /**
     * Scan up to {@code limit} non-terminal instances whose {@code updated_time} predates
     * {@code threshold} and emit a warning log for each — does not mutate state.
     *
     * @return number of stuck instances observed
     */
    public int sweepStuckInstances(Instant threshold, int limit) {
        LocalDateTime cutoff = LocalDateTime.ofInstant(threshold, ZoneId.systemDefault());
        List<FlowInstance> stuck = instanceService.findStuckInstances(cutoff, limit);
        for (FlowInstance instance : stuck) {
            log.warn("Flow instance appears stuck: instanceId={} status={} updatedTime={}",
                    instance.getInstanceId(), instance.getStatus(), instance.getUpdatedTime());
        }
        return stuck.size();
    }
}
