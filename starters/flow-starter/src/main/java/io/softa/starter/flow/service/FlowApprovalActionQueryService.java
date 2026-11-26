package io.softa.starter.flow.service;

import java.util.List;

import io.softa.starter.flow.dto.FlowApprovalActionView;

/**
 * Query service for persistent flow approval record read models.
 */
public interface FlowApprovalActionQueryService {

    /**
     * Returns the full approval history for a runtime instance (cross-actor view).
     * Restricted to instance participants/initiator.
     *
     * @param instanceId  the runtime instance id
     * @param requesterId the authenticated caller's user id (for participant scoping)
     */
    List<FlowApprovalActionView> getByInstanceId(String instanceId, String requesterId);
}
