package io.softa.starter.flow.service;

import java.util.List;

import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Page;
import io.softa.starter.flow.entity.FlowEvent;

/**
 * Service interface for trigger event log persistence and queries.
 */
public interface FlowEventService {

    /**
     * Record a trigger event log entry.
     *
     * @param event the event to persist
     */
    void recordEvent(FlowEvent event);

    /**
     * Paged event query for monitoring views.
     */
    default Page<FlowEvent> searchEvents(FlexQuery query, Page<FlowEvent> page) {
        return page;
    }

    /**
     * List events by flow code, ordered by eventTime descending.
     *
     * @param flowCode the flow code
     * @return list of events
     */
    List<FlowEvent> listByFlowCode(String flowCode);

    /**
     * List events by source model and row id.
     *
     * @param sourceModel the source model
     * @param sourceRowId the source row id
     * @return list of events
     */
    List<FlowEvent> listBySource(String sourceModel, String sourceRowId);

    /**
     * List events by instance id.
     *
     * @param instanceId the runtime instance id
     * @return list of events
     */
    List<FlowEvent> listByInstanceId(String instanceId);
}

