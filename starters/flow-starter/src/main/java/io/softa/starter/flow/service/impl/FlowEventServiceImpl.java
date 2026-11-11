package io.softa.starter.flow.service.impl;

import org.springframework.stereotype.Service;

import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.flow.entity.FlowEvent;
import io.softa.starter.flow.service.FlowEventService;

/**
 * ORM-backed trigger event log service.
 */
@Service
public class FlowEventServiceImpl extends EntityServiceImpl<FlowEvent, Long>
        implements FlowEventService {

    @Override
    public void recordEvent(FlowEvent event) {
        Long id = this.createOne(event);
        event.setId(id);
    }
}
