package io.softa.starter.message.mq.outbox;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.message.mq.TopicRoute;

/**
 * Outbox CRUD + enqueue helper.
 */
public interface OutboxService extends EntityService<OutboxEntry, Long> {

    /**
     * Persist a new outbox row for the given aggregate. Intended to be called
     * from within the same {@code @Transactional} boundary that wrote the
     * business record, guaranteeing atomicity.
     *
     * @param aggregateType human-readable model name (e.g. {@code MailSendRecord})
     * @param aggregateId   id of the business record
     * @param route         logical topic route
     * @param payload       the message body (JSON, will be written verbatim)
     * @return the newly assigned outbox id
     */
    Long enqueue(String aggregateType, Long aggregateId, TopicRoute route, String payload);

    /**
     * Enqueue a delayed outbox row. The {@link OutboxPublisher}'s poll query
     * filters on {@code next_attempt_at <= now()}, so the entry stays untouched
     * until {@code fireAt} elapses — that's how RETRY messages wait without
     * needing Pulsar's delayed delivery.
     *
     * @param fireAt earliest wall-clock time the row may be published;
     *               pass {@code null} or a past timestamp to behave like
     *               {@link #enqueue(String, Long, TopicRoute, String)}
     */
    Long enqueueAt(String aggregateType, Long aggregateId, TopicRoute route,
                   String payload, LocalDateTime fireAt);

    /**
     * Due {@code NEW} rows restricted to the given routes. The publisher passes
     * only broker-available routes so rows on unconfigured routes never occupy
     * the (id-ordered, limited) scan batch — they'd otherwise starve routable
     * rows behind them until an operator supplies the missing topic.
     */
    List<OutboxEntry> findDueNew(int limit, LocalDateTime now, Collection<TopicRoute> routes);

    List<OutboxEntry> findStalePublishing(LocalDateTime threshold, int limit);

    boolean markPublishing(Long id, long expectedVersion);

    boolean markPublished(Long id, long expectedVersion);

    boolean markNew(Long id, long expectedVersion, int attempts, String lastError, LocalDateTime nextAttemptAt);

    void markDead(Long id, long expectedVersion, int attempts, String lastError);
}
