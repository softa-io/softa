package io.softa.starter.message.quota.service.impl;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.softa.framework.base.context.ContextUtils;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.exception.VersionException;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.message.quota.entity.TenantMessageUsage;
import io.softa.starter.message.quota.service.TenantMessageUsageService;

/**
 * Implementation of {@link TenantMessageUsageService}.
 * <p>
 * The check-and-increment reuses the ORM's {@code versionLock} CAS — the same
 * mechanism the delivery state machine trusts — instead of raw SQL: read the
 * row, check the ceiling, write {@code used + 1} carrying the read
 * {@code version}; a concurrent increment makes the write miss
 * ({@code VersionException}) and the attempt is retried on a fresh read.
 * Each attempt runs in its own {@code REQUIRES_NEW} transaction because a
 * {@code VersionException} crossing a joined transaction proxy would mark an
 * ambient transaction rollback-only even when caught.
 * <p>
 * Ledger writes run with permission checks skipped and no tenant stamping
 * concerns (the model is not {@code multiTenant}): a send is charged while it
 * is prepared, in its record's tenant context, while the ledger is
 * platform-owned bookkeeping the sender needs no grant for.
 */
@Slf4j
@Service
public class TenantMessageUsageServiceImpl extends EntityServiceImpl<TenantMessageUsage, Long>
        implements TenantMessageUsageService {

    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");
    /**
     * Lost races in a row before one attempt gives up. Every delivery worker of every instance charges
     * the same row while one bucket's sends are being prepared, so losing a race is ordinary.
     */
    private static final int MAX_CAS_ATTEMPTS = 5;

    /** Self-reference so {@code REQUIRES_NEW} on {@link #tryConsumeOnce} crosses the proxy. */
    @Lazy
    @Autowired
    private TenantMessageUsageService self;

    @Override
    public void consume(String channel, long tenantId, Long limit) {
        String month = MONTH_FMT.format(YearMonth.now());
        for (int attempt = 1; attempt <= MAX_CAS_ATTEMPTS; attempt++) {
            try {
                self.tryConsumeOnce(channel, tenantId, month, limit);
                return;
            } catch (VersionException lostRace) {
                // A concurrent send of the same bucket won the CAS — re-read
                // and try again. Safe to catch here: the attempt's own
                // REQUIRES_NEW transaction already rolled back, no ambient
                // transaction was touched.
                log.debug("Quota CAS retry {}/{} for {} bucket {}", attempt, MAX_CAS_ATTEMPTS,
                        channel, tenantId);
            }
        }
        // Not a BusinessException: the delivery processors read that as "the quota is used up" and fail
        // the record for good. Losing the race this often is contention, which passes; as a
        // VersionException the attempt fails like any database hiccup and the record goes to RETRY.
        throw new VersionException("The monthly {0} usage counter of bucket {1} kept changing under "
                + "concurrent sends ({2} attempts); try again.", channel, tenantId, MAX_CAS_ATTEMPTS);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void tryConsumeOnce(String channel, long tenantId, String month, Long limit) {
        ContextUtils.withoutPermissionChecks(() -> {
            boolean mail = "mail".equals(channel);
            Optional<TenantMessageUsage> existing = searchOne(new Filters()
                    .eq(TenantMessageUsage::getTenantId, tenantId)
                    .eq(TenantMessageUsage::getMonth, month));
            if (existing.isEmpty()) {
                rejectIfExhausted(channel, tenantId, limit, 0L);
                TenantMessageUsage row = new TenantMessageUsage();
                row.setTenantId(tenantId);
                row.setMonth(month);
                row.setMailUsed(mail ? 1L : 0L);
                row.setSmsUsed(mail ? 0L : 1L);
                if (mail) {
                    row.setMailMonthlyLimit(limit);
                } else {
                    row.setSmsMonthlyLimit(limit);
                }
                try {
                    createOne(row);
                    return;
                } catch (RuntimeException insertRace) {
                    // Unique(tenantId, month): a concurrent first send created the
                    // row between our read and insert. Only that exact race is
                    // retryable — anything else propagates.
                    if (searchOne(new Filters()
                            .eq(TenantMessageUsage::getTenantId, tenantId)
                            .eq(TenantMessageUsage::getMonth, month)).isEmpty()) {
                        throw insertRace;
                    }
                    throw new VersionException("Lost the first-send insert race; retrying as an update.");
                }
            }
            TenantMessageUsage row = existing.get();
            long used = mail
                    ? (row.getMailUsed() != null ? row.getMailUsed() : 0L)
                    : (row.getSmsUsed() != null ? row.getSmsUsed() : 0L);
            rejectIfExhausted(channel, tenantId, limit, used);
            // Patch entity: id + the read version (the CAS guard) + only this
            // channel's columns — the other channel's counters stay untouched.
            TenantMessageUsage patch = new TenantMessageUsage();
            patch.setId(row.getId());
            patch.setVersion(row.getVersion());
            if (mail) {
                patch.setMailUsed(used + 1);
                patch.setMailMonthlyLimit(limit);
            } else {
                patch.setSmsUsed(used + 1);
                patch.setSmsMonthlyLimit(limit);
            }
            updateOne(patch);
        });
    }

    private static void rejectIfExhausted(String channel, long tenantId, Long limit, long used) {
        if (limit != null && used >= limit) {
            log.warn("Monthly {} quota exhausted for tenant bucket {}: limit={}", channel, tenantId, limit);
            throw new BusinessException(
                    "Monthly {0} send quota ({1}) is exhausted for this month. "
                    + "Contact platform operations to raise the limit.", channel, limit);
        }
    }

    @Override
    public Optional<TenantMessageUsage> findByBucketAndMonth(long tenantId, YearMonth month) {
        return ContextUtils.withoutPermissionChecks(() -> searchOne(new Filters()
                .eq(TenantMessageUsage::getTenantId, tenantId)
                .eq(TenantMessageUsage::getMonth, MONTH_FMT.format(month))));
    }
}
