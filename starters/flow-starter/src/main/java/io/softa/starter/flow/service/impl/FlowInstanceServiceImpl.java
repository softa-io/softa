package io.softa.starter.flow.service.impl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.domain.Page;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.service.FlowInstanceService;

/**
 * ORM-backed flow instance service.
 */
@Service
public class FlowInstanceServiceImpl extends EntityServiceImpl<FlowInstance, Long>
        implements FlowInstanceService {

    @Override
    public FlowInstance saveInstance(FlowInstance instance) {
        if (instance.getId() != null) {
            if (this.updateOne(instance, false)) {
                advanceVersion(instance);
            }
            return instance;
        }
        // Check if one already exists by instanceId
        Optional<FlowInstance> existing = findByInstanceId(instance.getInstanceId());
        if (existing.isPresent()) {
            instance.setId(existing.get().getId());
            if (instance.getVersion() == null) {
                instance.setVersion(existing.get().getVersion());
            }
            if (this.updateOne(instance, false)) {
                advanceVersion(instance);
            }
            return instance;
        }
        Long id = this.createOne(instance);
        instance.setId(id);
        if (instance.getVersion() == null) {
            instance.setVersion(1);
        }
        return instance;
    }

    private static void advanceVersion(FlowInstance instance) {
        if (instance.getVersion() != null) {
            instance.setVersion(instance.getVersion() + 1);
        }
    }

    @Override
    public Optional<FlowInstance> findByInstanceId(String instanceId) {
        Filters filters = new Filters().eq(FlowInstance::getInstanceId, instanceId);
        return this.searchOne(filters);
    }

    @Override
    public List<FlowInstance> findByFlowCode(String flowCode) {
        Filters filters = new Filters().eq(FlowInstance::getFlowCode, flowCode);
        return this.searchList(filters);
    }

    @Override
    public List<FlowInstance> findByStatus(FlowExecutionStatus status) {
        Filters filters = new Filters().eq(FlowInstance::getStatus, status);
        return this.searchList(filters);
    }

    @Override
    public List<FlowInstance> findByModelName(String modelName) {
        Filters filters = new Filters().eq(FlowInstance::getModelName, modelName);
        return this.searchList(filters);
    }

    @Override
    public List<FlowInstance> findByModelNameAndRowId(String modelName, String rowId) {
        Filters filters = new Filters()
                .eq(FlowInstance::getModelName, modelName)
                .eq(FlowInstance::getRowId, rowId);
        return this.searchList(filters);
    }

    @Override
    public List<FlowInstance> findByInitiatorId(String initiatorId) {
        Filters filters = new Filters().eq(FlowInstance::getInitiatorId, initiatorId);
        return this.searchList(filters);
    }

    @Override
    public List<FlowInstance> findDueTimers(LocalDateTime now, int limit) {
        Filters filters = new Filters()
                .eq(FlowInstance::getStatus, FlowExecutionStatus.WAITING)
                .le(FlowInstance::getNextFireAt, now);
        Orders orders = Orders.ofAsc(FlowInstance::getNextFireAt);
        FlexQuery query = new FlexQuery(filters, orders);
        query.setLimitSize(limit);
        return this.searchList(query);
    }

    @Override
    public long countDueTimers(LocalDateTime now) {
        Filters filters = new Filters()
                .eq(FlowInstance::getStatus, FlowExecutionStatus.WAITING)
                .le(FlowInstance::getNextFireAt, now);
        return this.count(filters);
    }

    @Override
    public long countByStatus(FlowExecutionStatus status) {
        Filters filters = new Filters().eq(FlowInstance::getStatus, status);
        return this.count(filters);
    }

    @Override
    public Page<FlowInstance> searchInstances(FlexQuery query, Page<FlowInstance> page) {
        return this.searchPage(query, page);
    }

    @Override
    public List<FlowInstance> findStuckInstances(LocalDateTime threshold, int limit) {
        Filters filters = new Filters()
                .in(FlowInstance::getStatus, List.of(
                        FlowExecutionStatus.RUNNING,
                        FlowExecutionStatus.WAITING))
                .lt(FlowInstance::getUpdatedTime, threshold);
        Orders orders = Orders.ofAsc(FlowInstance::getUpdatedTime);
        FlexQuery query = new FlexQuery(filters, orders);
        query.setLimitSize(limit);
        return this.searchList(query);
    }
}
