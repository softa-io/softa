package io.softa.starter.message.quota.service;

import java.time.YearMonth;
import java.util.Optional;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.message.quota.entity.TenantMessageUsage;

/**
 * The monthly send ledger behind quota enforcement: durable per-bucket
 * per-month counters, incremented via the ORM's optimistic-lock CAS
 * (the same {@code versionLock} pattern as the delivery state machine).
 */
public interface TenantMessageUsageService extends EntityService<TenantMessageUsage, Long> {

    /**
     * Consume one unit of the CURRENT month's ledger for {@code channel}
     * ("mail" / "sms") from bucket {@code tenantId}, enforcing {@code limit}
     * (null = unlimited — the count still advances for reporting).
     * <p>
     * Check-and-increment is an optimistic-lock CAS with a bounded retry
     * loop; each attempt commits in its own {@code REQUIRES_NEW} transaction
     * so a version conflict never poisons an ambient transaction.
     * Consequence: when the send fails after its increment committed — its
     * prepared record cannot be written back — the ledger keeps the
     * increment: a small over-count, reconcilable against the send-record
     * tables in the same database.
     *
     * @param limit the currently resolved ceiling, snapshotted onto the row
     * @throws io.softa.framework.base.exception.BusinessException when the
     *         month's ceiling is exhausted (terminal — never retried)
     * @throws io.softa.framework.base.exception.VersionException when
     *         concurrent sends of the bucket kept winning the race (transient
     *         — the send is retried)
     */
    void consume(String channel, long tenantId, Long limit);

    /**
     * The ledger row of one bucket and month, if any sends were charged.
     * Read with permission checks skipped — serves enforcement and the usage
     * endpoint under the caller's own context.
     */
    Optional<TenantMessageUsage> findByBucketAndMonth(long tenantId, YearMonth month);

    /**
     * One CAS attempt in its own {@code REQUIRES_NEW} transaction: create the
     * month's row (first send) or conditionally increment it. Public only so
     * the transactional proxy applies — callers use {@link #consume}.
     *
     * @throws io.softa.framework.base.exception.VersionException on a lost
     *         race (caller retries)
     */
    void tryConsumeOnce(String channel, long tenantId, String month, Long limit);
}
