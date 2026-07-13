package io.softa.starter.flow.service;

import java.util.List;

import io.softa.framework.orm.domain.Page;
import io.softa.starter.flow.dto.FlowApprovalTaskView;

/**
 * Query service for persistent flow approval task read models.
 */
public interface FlowApprovalTaskQueryService {

    List<FlowApprovalTaskView> getPendingTasks(String actorId);

    List<FlowApprovalTaskView> getPendingTasks(String actorId, String flowCode, String instanceId, String nodeId);

    Page<FlowApprovalTaskView> pagePendingTasks(String actorId, String flowCode, String instanceId, String nodeId,
                                                Integer pageNumber, Integer pageSize);

    List<FlowApprovalTaskView> getCompletedTasks(String actorId);

    List<FlowApprovalTaskView> getCompletedTasks(String actorId, String flowCode, String instanceId, String nodeId);

    Page<FlowApprovalTaskView> pageCompletedTasks(String actorId, String flowCode, String instanceId, String nodeId,
                                                  Integer pageNumber, Integer pageSize);

    List<FlowApprovalTaskView> getCcTasks(String actorId, Boolean read);

    List<FlowApprovalTaskView> getCcTasks(String actorId, Boolean read, String flowCode, String instanceId, String nodeId);

    Page<FlowApprovalTaskView> pageCcTasks(String actorId, Boolean read, String flowCode, String instanceId,
                                           String nodeId, Integer pageNumber, Integer pageSize);

    /**
     * Returns all approval tasks for a runtime instance (cross-actor view).
     * Restricted to instance participants/initiator.
     *
     * @param instanceId  the runtime instance id
     * @param requesterId the authenticated caller's user id (for participant scoping)
     */
    List<FlowApprovalTaskView> getTasksByInstanceId(String instanceId, String requesterId);
}
