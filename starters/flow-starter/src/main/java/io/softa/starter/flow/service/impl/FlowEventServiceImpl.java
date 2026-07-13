package io.softa.starter.flow.service.impl;

import java.util.List;
import org.springframework.stereotype.Service;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Page;
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

    @Override
    public Page<FlowEvent> searchEvents(FlexQuery query, Page<FlowEvent> page) {
        return this.searchPage(query, page);
    }

    @Override
    public List<FlowEvent> listByFlowCode(String flowCode) {
        Filters filters = new Filters().eq(FlowEvent::getFlowCode, flowCode);
        return this.searchList(filters);
    }

    @Override
    public List<FlowEvent> listBySource(String sourceModel, String sourceRowId) {
        Filters filters = new Filters()
                .eq(FlowEvent::getSourceModel, sourceModel)
                .eq(FlowEvent::getSourceRowId, sourceRowId);
        return this.searchList(filters);
    }

    @Override
    public List<FlowEvent> listByInstanceId(String instanceId) {
        Filters filters = new Filters().eq(FlowEvent::getInstanceId, instanceId);
        return this.searchList(filters);
    }
}

