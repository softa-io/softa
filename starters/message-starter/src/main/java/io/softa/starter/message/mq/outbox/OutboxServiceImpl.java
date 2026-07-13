package io.softa.starter.message.mq.outbox;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

import io.softa.framework.base.exception.VersionException;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.message.mq.TopicRoute;

@Service
public class OutboxServiceImpl extends EntityServiceImpl<OutboxEntry, Long>
        implements OutboxService {

    @Override
    public Long enqueue(String aggregateType, Long aggregateId, TopicRoute route, String payload) {
        return enqueueAt(aggregateType, aggregateId, route, payload, null);
    }

    @Override
    public Long enqueueAt(String aggregateType, Long aggregateId, TopicRoute route,
                          String payload, LocalDateTime fireAt) {
        OutboxEntry entry = new OutboxEntry();
        entry.setAggregateType(aggregateType);
        entry.setAggregateId(aggregateId);
        entry.setRoute(route);
        entry.setPayload(payload);
        entry.setStatus(OutboxStatus.NEW);
        entry.setAttempts(0);
        entry.setVersion(0L);
        if (fireAt != null && fireAt.isAfter(LocalDateTime.now())) {
            entry.setNextAttemptAt(fireAt);
        }
        return createOne(entry);
    }

    @Override
    public List<OutboxEntry> findDueNew(int limit, LocalDateTime now, Collection<TopicRoute> routes) {
        Filters due = new Filters()
                .eq(OutboxEntry::getStatus, OutboxStatus.NEW)
                .in(OutboxEntry::getRoute, List.copyOf(routes))
                .and(Filters.or(
                        new Filters().isNotSet(OutboxEntry::getNextAttemptAt),
                        new Filters().le(OutboxEntry::getNextAttemptAt, now)));
        FlexQuery query = new FlexQuery(due, Orders.ofAsc(OutboxEntry::getId));
        query.setLimitSize(limit);
        return searchList(query);
    }

    @Override
    public List<OutboxEntry> findStalePublishing(LocalDateTime threshold, int limit) {
        Filters stale = new Filters()
                .eq(OutboxEntry::getStatus, OutboxStatus.PUBLISHING)
                .lt(OutboxEntry::getUpdatedTime, threshold);
        FlexQuery query = new FlexQuery(stale, Orders.ofAsc(OutboxEntry::getUpdatedTime));
        query.setLimitSize(limit);
        return searchList(query);
    }

    @Override
    public boolean markPublishing(Long id, long expectedVersion) {
        Map<String, Object> patch = versionedPatch(id, expectedVersion);
        patch.put("status", OutboxStatus.PUBLISHING);
        return updateVersioned(patch);
    }

    @Override
    public boolean markPublished(Long id, long expectedVersion) {
        Map<String, Object> patch = versionedPatch(id, expectedVersion);
        patch.put("status", OutboxStatus.PUBLISHED);
        patch.put("publishedAt", LocalDateTime.now());
        return updateVersioned(patch);
    }

    @Override
    public boolean markNew(Long id, long expectedVersion, int attempts, String lastError, LocalDateTime nextAttemptAt) {
        Map<String, Object> patch = versionedPatch(id, expectedVersion);
        patch.put("status", OutboxStatus.NEW);
        patch.put("attempts", attempts);
        patch.put("lastError", lastError);
        patch.put("nextAttemptAt", nextAttemptAt);
        return updateVersioned(patch);
    }

    @Override
    public void markDead(Long id, long expectedVersion, int attempts, String lastError) {
        Map<String, Object> patch = versionedPatch(id, expectedVersion);
        patch.put("status", OutboxStatus.DEAD);
        patch.put("attempts", attempts);
        patch.put("lastError", lastError);
        updateVersioned(patch);
    }

    private Map<String, Object> versionedPatch(Long id, long expectedVersion) {
        Map<String, Object> patch = new HashMap<>();
        patch.put(ModelConstant.ID, id);
        patch.put(ModelConstant.VERSION, expectedVersion);
        return patch;
    }

    private boolean updateVersioned(Map<String, Object> patch) {
        try {
            return modelService.updateOne(modelName, patch);
        } catch (VersionException e) {
            return false;
        }
    }
}
