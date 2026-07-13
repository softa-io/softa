package io.softa.starter.flow.service;

import java.util.List;

import io.softa.framework.orm.domain.Page;
import io.softa.starter.flow.dto.FlowApprovalRecordView;
import io.softa.starter.flow.dto.FlowSentCcView;

/**
 * Query service for persistent flow approval record read models.
 */
public interface FlowApprovalRecordQueryService {

    /**
     * Returns the full approval history for a runtime instance (cross-actor view).
     * Restricted to instance participants/initiator.
     *
     * @param instanceId  the runtime instance id
     * @param requesterId the authenticated caller's user id (for participant scoping)
     */
    List<FlowApprovalRecordView> getByInstanceId(String instanceId, String requesterId);

    /**
     * Paged approval history for one actor, newest first. Filters are pushed to the query so a
     * long-tenured actor's history is never loaded into memory in full.
     */
    Page<FlowApprovalRecordView> getHistory(String actorId, String flowCode, String instanceId, String nodeId,
                                            Integer pageNumber, Integer pageSize);

    List<FlowSentCcView> getSentCcHistory(String actorId, Boolean read, String flowCode, String instanceId, String nodeId);
}

