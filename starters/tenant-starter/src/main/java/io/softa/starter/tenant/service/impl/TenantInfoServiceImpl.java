package io.softa.starter.tenant.service.impl;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;

import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.constant.RedisConstant;
import io.softa.framework.base.enums.Timezone;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.jdbc.JdbcService;
import io.softa.framework.orm.service.CacheService;
import io.softa.framework.orm.service.TenantInfoService;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.tenant.entity.TenantInfo;
import io.softa.starter.tenant.enums.TenantStatus;

/**
 * Default implementation of the framework {@link TenantInfoService} SPI, living in
 * tenant-starter alongside the {@link TenantInfo} entity. The framework SPI exposes only
 * non-entity methods (active ids / isTenantActive / deactivate); the entity-returning
 * {@code getTenantInfo} / {@code getActiveTenantList} are internal helpers here.
 */
@Slf4j
@Component
public class TenantInfoServiceImpl extends EntityServiceImpl<TenantInfo, Long> implements TenantInfoService {

    @Autowired
    private CacheService cacheService;

    @Autowired
    private JdbcService<?> jdbcService;

    @Autowired
    private PlatformTenantCreator platformTenantCreator;

    @Override
    public List<Long> getActiveTenantIds() {
        // Try Redis cache first
        List<Long> tenantIds = cacheService.get(RedisConstant.TENANT_IDS, new TypeReference<>() {});
        if (tenantIds != null && !tenantIds.isEmpty()) {
            return tenantIds;
        }
        // Fallback to database query
        List<TenantInfo> tenants = getActiveTenantList();
        tenantIds = tenants.stream().map(TenantInfo::getId).toList();
        if (!tenantIds.isEmpty()) {
            cacheService.save(RedisConstant.TENANT_IDS, tenantIds, RedisConstant.ONE_MONTH);
        }
        return tenantIds;
    }

    /** Read from the table rather than the active-id cache: suspended and closed tenants are not in that one. */
    @Override
    public List<Long> getProvisionedTenantIds() {
        return jdbcService.selectMetaEntityList(TenantInfo.class, null).stream()
                .filter(tenant -> tenant.getStatus() == TenantStatus.ACTIVE
                        || tenant.getStatus() == TenantStatus.SUSPENDED
                        || tenant.getStatus() == TenantStatus.CLOSED)
                .map(TenantInfo::getId)
                .toList();
    }

    @Override
    public String getDefaultCountry(Long tenantId) {
        if (tenantId == null) {
            return null;
        }
        TenantInfo tenantInfo = getTenantInfo(tenantId);
        return tenantInfo == null ? null : tenantInfo.getDefaultCountry();
    }

    @Override
    public Timezone getDefaultTimezone(Long tenantId) {
        if (tenantId == null) {
            return null;
        }
        TenantInfo tenantInfo = getTenantInfo(tenantId);
        return tenantInfo == null ? null : tenantInfo.getDefaultTimezone();
    }

    @Override
    public String getTenantName(Long tenantId) {
        if (tenantId == null) {
            return null;
        }
        TenantInfo tenantInfo = getTenantInfo(tenantId);
        return tenantInfo == null ? null : tenantInfo.getName();
    }

    @Override
    public boolean isTenantActive(Long tenantId) {
        if (tenantId == null) {
            return false;
        }
        TenantInfo tenantInfo = getTenantInfo(tenantId);
        return tenantInfo != null && TenantStatus.ACTIVE.equals(tenantInfo.getStatus());
    }

    /**
     * Reads through the same per-tenant cache as {@link #isTenantActive}, so it costs nothing extra on the
     * login path.
     *
     * <p>Distinct from {@link #isTenantActive}: a SUSPENDED or CLOSED tenant IS built — it just may not be
     * used. Callers that need "has this workspace been set up" (creating its first admin, say) must not
     * also refuse a suspended one, so the two questions stay separate even though one field answers both.
     *
     * <p>Null counts as built. A tenant row written before this axis existed has no status, and reading
     * that as "not built" would refuse every pre-existing customer on the deploy that introduced it.
     */
    @Override
    public boolean isTenantProvisioned(Long tenantId) {
        if (tenantId == null) {
            return false;
        }
        TenantInfo tenantInfo = getTenantInfo(tenantId);
        if (tenantInfo == null) {
            return false;
        }
        TenantStatus status = tenantInfo.getStatus();
        return status != TenantStatus.DRAFT && status != TenantStatus.INITIALIZING;
    }

    @Override
    public void deactivate(Long tenantId) {
        transitionTo(tenantId, TenantStatus.SUSPENDED, TenantStatus.ACTIVE);
    }

    /**
     * Reinstate a suspended OR closed tenant. Closing is deliberately non-destructive — it changes the
     * status and leaves every row in place — so it is reversible, and refusing to reverse it would strand a
     * recoverable workspace behind "create a new tenant". A close that must also erase data is a separate
     * operation still to be defined; until it exists, nothing here is irreversible.
     */
    @Override
    public void activate(Long tenantId) {
        transitionTo(tenantId, TenantStatus.ACTIVE, TenantStatus.SUSPENDED, TenantStatus.CLOSED);
    }

    @Override
    public void close(Long tenantId) {
        transitionTo(tenantId, TenantStatus.CLOSED, TenantStatus.ACTIVE, TenantStatus.SUSPENDED);
    }

    /**
     * The one write path for {@link TenantStatus}: check the transition is legal, stamp the timestamp
     * belonging to the target status and clear the other two, then evict the tenant caches.
     *
     * <p>Exactly one timestamp is ever set, which keeps the trio a function of the status — an ACTIVE
     * tenant can never display a suspended time. Round-trip history (how often, how long) is the change
     * log's job; three flat columns could not hold it anyway.
     *
     * <p>The eviction is the part that cannot be skipped: {@code isTenantActive()} reads through the
     * cache, so without it a suspension does not take effect until the entry expires and the tenant's
     * users keep working meanwhile.
     */
    private void transitionTo(Long tenantId, TenantStatus target, TenantStatus... allowedFrom) {
        // Suspending or closing the platform tenant would lock the platform admins out permanently —
        // they could not log in to undo it, and there is no other path back.
        Assert.notTrue(BaseConstant.PLATFORM_TENANT_ID.equals(tenantId),
                "The platform tenant's operational status cannot be changed.");
        TenantInfo tenant = this.getById(tenantId).orElse(null);
        Assert.notNull(tenant, "Tenant not found for tenantId: {0}", tenantId);
        if (target.equals(tenant.getStatus())) {
            return;   // idempotent — already there, nothing to write or evict
        }
        Assert.isTrue(Arrays.asList(allowedFrom).contains(tenant.getStatus()),
                "Tenant {0} cannot move from {1} to {2}.", tenantId, tenant.getStatus(), target);

        TenantStatus previous = tenant.getStatus();
        stampAndSet(tenant, target);
        this.updateOne(tenant);

        cacheService.clear(RedisConstant.TENANT_INFO + tenantId);
        cacheService.clear(RedisConstant.TENANT_IDS);
        log.info("Tenant {} status {} -> {}", tenantId, previous, target);
    }

    /**
     * Write the tenant's setup-driven status — the states the seeders move it through
     * (DRAFT / INITIALIZING / ACTIVE). Idempotent: a no-op when already at the target, so it is safe under
     * MQ redelivery and concurrent seeders reporting at once.
     *
     * <p>Separate from {@link #transitionTo}, which guards operator-driven transitions against an expected
     * current state. Setup has no such expectation to assert — a redelivered completion legitimately arrives
     * at a tenant already ACTIVE — so this writes unconditionally and relies on the no-op instead.
     *
     * <p>Evicts the cached TenantInfo AND the active-id list: with one axis, reaching ACTIVE is what puts
     * the tenant into per-tenant cron fan-out, and a stale list would leave a freshly built tenant out of it
     * until the entry expired.
     */
    public void markStatus(Long tenantId, TenantStatus status) {
        // Same guard as transitionTo, for the same reason: the platform tenant has no way back if it is
        // moved out of ACTIVE, because the admins who would move it back log in through it.
        Assert.notTrue(BaseConstant.PLATFORM_TENANT_ID.equals(tenantId),
                "The platform tenant's status cannot be changed.");
        TenantInfo tenant = this.getById(tenantId).orElse(null);
        Assert.notNull(tenant, "Tenant not found for tenantId: {0}", tenantId);
        if (status.equals(tenant.getStatus())) {
            return;
        }
        // Setup never overrules an operator. Both concerns share one field now, so a completion arriving at a
        // suspended or closed tenant would otherwise reopen it — and completions DO arrive late: the seed
        // messages are at-least-once, so a redelivery after someone closed the tenant is ordinary, not
        // exotic. It would let users back into a workspace an operator had shut, silently.
        //
        // Ignored rather than rejected: the caller is an MQ consumer whose message is legitimately a
        // duplicate. Throwing would nack it and burn the redelivery budget on work that is already done.
        if (tenant.getStatus() == TenantStatus.SUSPENDED || tenant.getStatus() == TenantStatus.CLOSED) {
            log.info("Tenant {} is {}; leaving it there rather than moving it to {} — an operator's decision "
                    + "outranks setup progress", tenantId, tenant.getStatus(), status);
            return;
        }
        stampAndSet(tenant, status);
        this.updateOne(tenant);
        cacheService.clear(RedisConstant.TENANT_INFO + tenantId);
        cacheService.clear(RedisConstant.TENANT_IDS);
    }

    /**
     * Record that a tenant's setup failed: INITIALIZING becomes DRAFT, and nothing else moves.
     *
     * <p>A seeder reports its failure from an MQ consumer, and the message it failed on may be a duplicate
     * running beside the one that set the tenant up — a redelivery, or a retry announced while the first round
     * was still going. Written as {@link #markStatus} writes, that late failure turned a tenant that had just
     * become ACTIVE back into DRAFT and refused its users at login. So the move is one conditional update on
     * the current status rather than a read followed by a write: a tenant that is no longer setting up is left
     * as it is, however the two rounds interleave.
     *
     * <p>Only the status is written. A tenant in INITIALIZING carries none of the three status timestamps, and
     * DRAFT carries none either, so there is nothing to clear.
     *
     * @param tenantId the tenant whose setup failed
     * @return whether this call moved the tenant to DRAFT
     */
    public boolean markSetupFailed(Long tenantId) {
        Assert.notTrue(BaseConstant.PLATFORM_TENANT_ID.equals(tenantId),
                "The platform tenant's status cannot be changed.");
        TenantInfo patch = new TenantInfo();
        patch.setStatus(TenantStatus.DRAFT);
        int moved = this.updateByFilter(new Filters()
                .eq(TenantInfo::getId, tenantId)
                .eq(TenantInfo::getStatus, TenantStatus.INITIALIZING), patch);
        if (moved == 0) {
            return false;
        }
        cacheService.clear(RedisConstant.TENANT_INFO + tenantId);
        cacheService.clear(RedisConstant.TENANT_IDS);
        log.info("Tenant {} status {} -> {}", tenantId, TenantStatus.INITIALIZING, TenantStatus.DRAFT);
        return true;
    }

    /**
     * Set the status and rewrite the timestamp trio so exactly one of them is populated — the one matching
     * the new status.
     *
     * <p>Shared by both write paths on purpose. The trio is meant to be a function of the status: an ACTIVE
     * tenant still showing a suspended time reads as suspended to anyone scanning the list. When only
     * {@code transitionTo} did this, a tenant that reached ACTIVE by finishing its setup got no
     * {@code activatedTime} while one an operator activated got one — the same state in two shapes.
     */
    private void stampAndSet(TenantInfo tenant, TenantStatus target) {
        LocalDateTime now = LocalDateTime.now();
        tenant.setActivatedTime(target == TenantStatus.ACTIVE ? now : null);
        tenant.setSuspendedTime(target == TenantStatus.SUSPENDED ? now : null);
        tenant.setClosedTime(target == TenantStatus.CLOSED ? now : null);
        tenant.setStatus(target);
    }

    /**
     * Delegates to {@link PlatformTenantCreator}, which writes the row through JDBC because its id is
     * fixed. No cache to clear afterwards: {@link #getTenantInfo} only ever caches a row it found, so the
     * absence that came before is not remembered anywhere.
     */
    @Override
    public boolean ensurePlatformTenant() {
        return platformTenantCreator.createIfAbsent();
    }

    /** Cached single-tenant lookup — internal to tenant-starter (not part of the framework SPI). */
    public TenantInfo getTenantInfo(Long tenantId) {
        TenantInfo tenantInfo = cacheService.get(RedisConstant.TENANT_INFO + tenantId, TenantInfo.class);
        if (tenantInfo != null) {
            return tenantInfo;
        }
        tenantInfo = this.getById(tenantId).orElse(null);
        if (tenantInfo != null) {
            cacheService.save(RedisConstant.TENANT_INFO + tenantId, tenantInfo);
        }
        return tenantInfo;
    }

    /** All ACTIVE tenants — internal helper feeding {@link #getActiveTenantIds()}. */
    public List<TenantInfo> getActiveTenantList() {
        return jdbcService.selectMetaEntityList(TenantInfo.class, null)
                .stream()
                .filter(tenant -> TenantStatus.ACTIVE.equals(tenant.getStatus()))
                .toList();
    }
}
