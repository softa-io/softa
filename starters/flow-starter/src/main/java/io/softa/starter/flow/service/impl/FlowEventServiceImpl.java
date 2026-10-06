package io.softa.starter.flow.service.impl;

import java.util.Optional;
import org.springframework.stereotype.Service;

import io.softa.framework.orm.annotation.SkipPermissionCheck;
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

    /**
     * Engine bookkeeping write — exempt from the caller's row scope; see
     * {@code FlowInstanceServiceImpl.saveInstance} for why the post-write scope re-read cannot
     * succeed on a {@code Flow*} ledger table. Reached from {@code FlowAutomationService}, a
     * different bean, so the aspect fires.
     */
    @Override
    @SkipPermissionCheck
    public void recordEvent(FlowEvent event) {
        Long id = this.createOne(event);
        event.setId(id);
    }

    @Override
    public Page<FlowEvent> searchEvents(FlexQuery query, Page<FlowEvent> page) {
        return this.searchPage(query, page);
    }

    @Override
    public Optional<FlowEvent> findEventById(Long id) {
        return this.getById(id);
    }

}

