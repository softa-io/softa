package io.softa.starter.flow.service.impl;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

import io.softa.framework.base.utils.LambdaUtils;
import io.softa.framework.orm.annotation.SkipPermissionCheck;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.domain.Page;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.flow.dto.FlowInstanceSearchRequest;
import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.service.FlowInstanceService;

/**
 * ORM-backed flow instance service.
 */
@Service
public class FlowInstanceServiceImpl extends EntityServiceImpl<FlowInstance, Long>
        implements FlowInstanceService {

    /** Instance search projection — the heavy JSON state columns are deliberately excluded. */
    private static final List<String> INSTANCE_SUMMARY_FIELDS = List.of(
            LambdaUtils.getAttributeName(FlowInstance::getId),
            LambdaUtils.getAttributeName(FlowInstance::getInstanceId),
            LambdaUtils.getAttributeName(FlowInstance::getBundleId),
            LambdaUtils.getAttributeName(FlowInstance::getDesignId),
            LambdaUtils.getAttributeName(FlowInstance::getFlowCode),
            LambdaUtils.getAttributeName(FlowInstance::getFlowRevision),
            LambdaUtils.getAttributeName(FlowInstance::getTitle),
            LambdaUtils.getAttributeName(FlowInstance::getModelName),
            LambdaUtils.getAttributeName(FlowInstance::getRowId),
            LambdaUtils.getAttributeName(FlowInstance::getInitiatorId),
            LambdaUtils.getAttributeName(FlowInstance::getStatus),
            LambdaUtils.getAttributeName(FlowInstance::getFailedNodeId),
            LambdaUtils.getAttributeName(FlowInstance::getNextFireAt),
            LambdaUtils.getAttributeName(FlowInstance::getResubmissionCount),
            LambdaUtils.getAttributeName(FlowInstance::getCreatedTime),
            LambdaUtils.getAttributeName(FlowInstance::getUpdatedTime));

    /**
     * Engine bookkeeping write — exempt from the caller's row scope.
     *
     * <p>{@code ModelServiceImpl} re-reads every row it just wrote and refuses the write when the
     * caller cannot see it ({@code checkIdsFieldsAccess}, CREATE after {@code createList} and UPDATE
     * after {@code updateList}). That question is right for business data and wrong here:
     * {@code flow_instance} is the engine's own ledger, nobody grants a scope over it, and it carries
     * no column that could tie a row to the initiator — {@code modelName} / {@code rowId} /
     * {@code initiatorId} are plain strings, not relations. So {@code findReferencer} finds no owner,
     * {@code hasForwardAnchor} finds no anchor, and the check fails closed for every non-admin. Tenant
     * admins never saw it because {@code PermissionInfo.isAdmin} short-circuits ahead of the scope
     * engine; everyone else could not start a flow at all.
     *
     * <p>Annotated HERE, on the proxy-visible interface method, for the same reason the
     * {@code @Transactional} on {@code FlowApprovalTaskServiceImpl.onStateChanged} sits there: the
     * {@code this.createOne} / {@code this.updateOne} calls below are self-invocations that Spring's
     * proxy never intercepts, so the annotation would be silently inert on them. Callers reach this
     * method from {@code OrmFlowInstanceStore}, a different bean.
     *
     * <p>The flag gates row scope and field masking only. Tenant isolation rides {@code crossTenant},
     * which is untouched, so this cannot widen a write past the caller's own tenant.
     *
     * <p><b>Write side only.</b> Reads of the same tables are still row-scoped and still resolve to
     * {@code matchNone()} for a non-admin — an approval inbox comes back empty rather than refused.
     * Covering that needs the same treatment on every {@code Flow*} query entry point, or a
     * model-level exemption; neither is done here.
     */
    @Override
    @SkipPermissionCheck
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
    public List<FlowInstance> findByInstanceIds(Collection<String> instanceIds) {
        if (instanceIds == null || instanceIds.isEmpty()) {
            return List.of();
        }
        Filters filters = new Filters().in(FlowInstance::getInstanceId, List.copyOf(instanceIds));
        FlexQuery query = new FlexQuery(filters);
        query.select(INSTANCE_SUMMARY_FIELDS);
        return this.searchList(query);
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
    public Page<FlowInstance> searchSummaries(FlowInstanceSearchRequest request, String forcedInitiatorId) {
        Filters filters = new Filters();
        if (request.flowCode() != null) {
            filters.eq(FlowInstance::getFlowCode, request.flowCode());
        }
        if (request.designId() != null) {
            filters.eq(FlowInstance::getDesignId, request.designId());
        }
        if (request.status() != null) {
            filters.eq(FlowInstance::getStatus, request.status());
        }
        String initiatorId = forcedInitiatorId != null ? forcedInitiatorId : request.initiatorId();
        if (initiatorId != null) {
            filters.eq(FlowInstance::getInitiatorId, initiatorId);
        }
        if (request.modelName() != null) {
            filters.eq(FlowInstance::getModelName, request.modelName());
        }
        if (request.rowId() != null) {
            filters.eq(FlowInstance::getRowId, request.rowId());
        }
        if (request.createdFrom() != null) {
            filters.ge(FlowInstance::getCreatedTime, request.createdFrom());
        }
        if (request.createdTo() != null) {
            filters.le(FlowInstance::getCreatedTime, request.createdTo());
        }
        FlexQuery query = new FlexQuery(filters, Orders.ofDesc(FlowInstance::getCreatedTime));
        query.select(INSTANCE_SUMMARY_FIELDS);
        Page<FlowInstance> page = Page.of(
                request.pageNumber() == null ? 1 : request.pageNumber(),
                request.pageSize() == null ? 50 : request.pageSize());
        // Funnel through searchInstances so a replacement implementation that
        // intercepts raw monitoring queries sees this path too.
        return this.searchInstances(query, page);
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
