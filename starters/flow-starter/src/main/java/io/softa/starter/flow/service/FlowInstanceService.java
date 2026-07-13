package io.softa.starter.flow.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Page;
import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;

/**
 * Service interface for persistent flow instance operations.
 * <p>
 * Every method is abstract on purpose: core capabilities (timer sweeps, start-dedup,
 * monitoring) depend on these finders, so a replacement implementation that misses one
 * must fail to compile instead of silently returning empty results.
 */
public interface FlowInstanceService {

    /**
     * Save or update a flow instance entity.
     *
     * @param instance the instance entity to persist
     * @return the saved entity
     */
    FlowInstance saveInstance(FlowInstance instance);

    /**
     * Find a flow instance by its runtime instance id.
     *
     * @param instanceId the runtime instance UUID
     * @return the instance if found
     */
    Optional<FlowInstance> findByInstanceId(String instanceId);

    /**
     * Find all instances by flow code.
     *
     * @param flowCode the flow code
     * @return list of matching instances
     */
    List<FlowInstance> findByFlowCode(String flowCode);

    /**
     * Find all instances by execution status.
     *
     * @param status the execution status
     * @return list of matching instances
     */
    List<FlowInstance> findByStatus(FlowExecutionStatus status);

    /**
     * Find all instances by related model name.
     *
     * @param modelName the model name
     * @return list of matching instances
     */
    List<FlowInstance> findByModelName(String modelName);

    /**
     * Find all instances by related model name and row id.
     *
     * @param modelName the model name
     * @param rowId     the row data id
     * @return list of matching instances
     */
    List<FlowInstance> findByModelNameAndRowId(String modelName, String rowId);

    /**
     * Find all instances initiated by a specific user.
     *
     * @param initiatorId the initiator id
     * @return list of matching instances
     */
    List<FlowInstance> findByInitiatorId(String initiatorId);

    /**
     * Find WAITING instances with a due timer whose {@code next_fire_at} is at or before {@code now}.
     *
     * @param now   cutoff timestamp
     * @param limit maximum rows to return
     * @return list of due timer instances
     */
    List<FlowInstance> findDueTimers(LocalDateTime now, int limit);

    /**
     * Count WAITING instances with a due timer whose {@code next_fire_at} is at or before {@code now}.
     */
    long countDueTimers(LocalDateTime now);

    /**
     * Count instances currently in the given status.
     */
    long countByStatus(FlowExecutionStatus status);

    /**
     * Paged instance query for monitoring views.
     */
    Page<FlowInstance> searchInstances(FlexQuery query, Page<FlowInstance> page);

    /**
     * Find non-terminal instances (RUNNING / WAITING_*) whose last update time is before
     * {@code threshold} — i.e. instances that have been silent longer than expected and may
     * require operator attention.
     *
     * @param threshold updated-time cutoff
     * @param limit     maximum rows to return
     * @return list of candidate stuck instances
     */
    List<FlowInstance> findStuckInstances(LocalDateTime threshold, int limit);
}
