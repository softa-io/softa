package io.softa.starter.flow.service;

import io.softa.starter.flow.entity.FlowEvent;

/**
 * Service interface for trigger event log persistence. The log is read as the {@code FlowEvent} model, through the
 * generic model API.
 */
public interface FlowEventService {

    /**
     * Record a trigger event log entry.
     *
     * @param event the event to persist
     */
    void recordEvent(FlowEvent event);
}
