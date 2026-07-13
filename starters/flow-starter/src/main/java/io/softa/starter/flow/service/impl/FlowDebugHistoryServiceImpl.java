package io.softa.starter.flow.service.impl;

import java.util.List;
import org.springframework.stereotype.Service;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.flow.entity.FlowDebugHistory;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.service.FlowDebugHistoryService;

/**
 * ORM-backed debug execution history service.
 */
@Service
public class FlowDebugHistoryServiceImpl extends EntityServiceImpl<FlowDebugHistory, Long>
        implements FlowDebugHistoryService {

    @Override
    public void recordHistory(FlowDebugHistory history) {
        Long id = this.createOne(history);
        history.setId(id);
    }

    @Override
    public List<FlowDebugHistory> listByFlowCode(String flowCode) {
        Filters filters = new Filters().eq(FlowDebugHistory::getFlowCode, flowCode);
        FlexQuery query = new FlexQuery(filters, Orders.ofDesc(FlowDebugHistory::getStartTime));
        return this.searchList(query);
    }

    @Override
    public List<FlowDebugHistory> listByInstanceId(String instanceId) {
        Filters filters = new Filters().eq(FlowDebugHistory::getInstanceId, instanceId);
        return this.searchList(filters);
    }

    @Override
    public List<FlowDebugHistory> listByStatus(FlowExecutionStatus status) {
        Filters filters = new Filters().eq(FlowDebugHistory::getStatus, status);
        return this.searchList(filters);
    }
}

