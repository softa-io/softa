package io.softa.starter.flow.service;

import java.util.List;

import io.softa.framework.orm.domain.Page;
import io.softa.starter.flow.dto.FlowApprovalTaskSearchRequest;
import io.softa.starter.flow.dto.FlowApprovalTaskView;
import io.softa.starter.flow.dto.FlowInboxCountView;

/**
 * Query service for persistent flow approval task read models.
 */
public interface FlowApprovalTaskQueryService {

    /**
     * One page of an actor's own tasks in a box of the approval center: the box selects the actor's pending,
     * handled or CC tasks (CC read and unread), the request's filters narrow them — ANDed on, never replacing
     * the actor and the box — and its orders sort them, else the box's own order. Filters and orders may name only
     * fields a task stores; anything else is refused (400) by its name. Views enriched as the other reads'.
     *
     * @param actorId the actor whose tasks are read — the authenticated caller on the REST endpoint
     * @param request the box, the caller's filters and orders, and the page
     */
    Page<FlowApprovalTaskView> searchTasks(String actorId, FlowApprovalTaskSearchRequest request);

    /**
     * Returns all approval tasks for a runtime instance (cross-actor view).
     * Restricted to instance participants/initiator.
     *
     * @param instanceId  the runtime instance id
     * @param requesterId the authenticated caller's user id (for participant scoping)
     */
    List<FlowApprovalTaskView> getTasksByInstanceId(String instanceId, String requesterId);

    /**
     * Inbox badge counts (pending approvals + unread CC copies) for one actor.
     * Uses the same filter definitions as the paged queries.
     */
    FlowInboxCountView countInbox(String actorId);
}
