package io.softa.starter.message.mq.outbox;

import java.time.LocalDateTime;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.starter.message.mq.TopicRoute;

/**
 * Writes a send-record state change and its companion outbox row in a single
 * transaction — the atomic unit the transactional-outbox guarantee depends on.
 * <p>
 * This lives in its own bean (rather than as a {@code @Transactional} method on
 * the send services) so the transaction boundary is always crossed through the
 * Spring proxy. A send service invoking its <i>own</i> {@code @Transactional}
 * method would bypass the proxy (self-invocation), the advice would never run,
 * and the record insert + outbox insert would commit in two independent
 * transactions — a crash between them strands the record with no outbox row:
 * never delivered, never retried, and never recovered (the
 * {@code ZombieRecordSweeper} only revives {@code SENDING}, not {@code PENDING}
 * / {@code RETRY}).
 */
@Component
@RequiredArgsConstructor
public class OutboxRecordWriter {

    private final OutboxService outboxService;

    /**
     * Persist a new aggregate record (via {@code persist}) and enqueue its
     * outbox row, atomically. Returns the new record id.
     */
    @Transactional
    public Long persistAndEnqueue(Supplier<Long> persist, String aggregateType, TopicRoute route) {
        Long recordId = persist.get();
        outboxService.enqueue(aggregateType, recordId, route, payload(recordId));
        return recordId;
    }

    /**
     * Apply a CAS state transition (via {@code transition}) and, only if it
     * succeeds, enqueue a delayed outbox row — atomically. Returns the CAS
     * result so callers can branch on it.
     */
    @Transactional
    public boolean transitionAndEnqueueAt(BooleanSupplier transition, Long recordId,
                                          String aggregateType, TopicRoute route, LocalDateTime fireAt) {
        if (!transition.getAsBoolean()) {
            return false;
        }
        outboxService.enqueueAt(aggregateType, recordId, route, payload(recordId), fireAt);
        return true;
    }

    /** Build the outbox payload, carrying tenant / trace context for the consumer. */
    private String payload(Long recordId) {
        Context ctx = ContextHolder.getContext();
        Long tenantId = ctx != null ? ctx.getTenantId() : null;
        String traceId = ctx != null ? ctx.getTraceId() : null;
        return JsonUtils.objectToString(new OutboxMessage(recordId, tenantId, traceId));
    }
}
