package io.softa.starter.flow.service;

import io.softa.starter.flow.dto.FlowInboxView;

/**
 * Aggregates approval and CC task buckets into one inbox response.
 */
public interface FlowInboxService {

    FlowInboxView getInbox(String actorId,
                                  String flowCode,
                                  String instanceId,
                                  String nodeId,
                                  Boolean includeCompletedApprovals);

    FlowInboxView getInbox(String actorId,
                           String flowCode,
                           String instanceId,
                           String nodeId,
                           Boolean includeCompletedApprovals,
                           Integer pageNumber,
                           Integer pageSize);
}
