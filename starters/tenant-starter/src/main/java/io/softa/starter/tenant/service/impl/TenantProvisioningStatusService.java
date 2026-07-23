package io.softa.starter.tenant.service.impl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static io.softa.framework.base.context.ContextUtils.inSystemContext;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.tenant.config.TenantProvisioningProperties;
import io.softa.starter.tenant.entity.TenantInfo;
import io.softa.starter.tenant.entity.TenantSeedProgress;
import io.softa.starter.tenant.enums.SeederStatus;
import io.softa.starter.tenant.enums.TenantProvisioningStatus;

/**
 * Provisioning-status coordinator — the per-tenant "completion latch". Owns the {@link TenantSeedProgress}
 * ledger and folds per-seeder completions into the tenant's {@link TenantProvisioningStatus}. Framework-side
 * and business-agnostic: it only ever sees opaque {@code seederKey} strings + the app's expected-seeders set;
 * it never imports or switches on a business module.
 *
 * <p><b>Idempotency</b>: progress is upserted by {@code (tenantId, seederKey)} — redelivery re-writes DONE
 * without churn; readiness is a set-containment query over DONE rows (never a counter), so it is repeat-safe.
 *
 * <p><b>FAILED has a single authoritative source: {@link #failTimedOut()}</b>, the time-driven guard driven
 * by tenant-starter's own cron consumer (softa-self-sufficient — no dependency on the app opting into a DLQ).
 * The per-seeder failure path ({@link #markSeederFailed}) is currently unreachable — see its javadoc.
 *
 * <p><b>Context</b>: every public entry point runs {@code inSystemContext} because it is driven from a Pulsar
 * consumer (no ambient tenant context) and touches shared, non-multiTenant models ({@link TenantSeedProgress}
 * + {@code TenantInfo}, both tenant-scoped by an explicit column).
 */
@Slf4j
@Service
public class TenantProvisioningStatusService extends EntityServiceImpl<TenantSeedProgress, Long> {

    private final TenantInfoServiceImpl tenantInfoService;
    private final TenantProvisioningProperties props;

    public TenantProvisioningStatusService(TenantInfoServiceImpl tenantInfoService,
                                           TenantProvisioningProperties props) {
        this.tenantInfoService = tenantInfoService;
        this.props = props;
    }

    /**
     * Called at the end of {@code provision()}. No expected seeders (empty config — single-tenant / no-MQ,
     * or rollout Step 1) → straight to READY; otherwise INITIALIZING until the expected set reports done.
     */
    public void beginProvisioning(Long tenantId) {
        TenantProvisioningStatus initial = props.getExpectedSeeders().isEmpty()
                ? TenantProvisioningStatus.READY
                : TenantProvisioningStatus.INITIALIZING;
        inSystemContext(() -> {
            tenantInfoService.markProvisioningStatus(tenantId, initial);
            return null;
        });
    }

    /** A seeder finished for this tenant: record DONE (idempotent), flip to READY once all expected are done. */
    @Transactional
    public void markSeederReady(Long tenantId, String seederKey) {
        inSystemContext(() -> {
            upsertProgress(tenantId, seederKey, SeederStatus.DONE);
            Set<String> done = doneKeys(tenantId);
            if (done.containsAll(props.getExpectedSeeders())) {
                tenantInfoService.markProvisioningStatus(tenantId, TenantProvisioningStatus.READY);
                log.info("Tenant {} provisioning READY (done seeders {})", tenantId, done);
            }
            return null;
        });
    }

    /**
     * A seeder reported terminal failure ({@code SeederCompletedMessage.success=false}): record per-seeder
     * FAILED and flag the tenant.
     *
     * <p><b>Currently unreachable, by design.</b> Seeders publish only {@code success=true}; a seed failure is
     * an exception → Pulsar redelivery (eventual consistency), never a {@code success=false} report. So the
     * {@code SeederCompletedCoordinator} else-branch, this method, and {@link SeederStatus#FAILED} are not
     * exercised today — the authoritative FAILED source is the time-driven {@link #failTimedOut()} guard
     * (tenant-level). This path is retained (not removed) as the symmetric hook for a FUTURE DLQ integration:
     * a redelivery-exhausted dead-letter handler would publish {@code success=false} to mark the exact failed
     * seeder. If that never lands, delete this method + the else-branch + {@code SeederStatus.FAILED} +
     * {@code SeederCompletedMessage.success} together.
     */
    @Transactional
    public void markSeederFailed(Long tenantId, String seederKey) {
        inSystemContext(() -> {
            upsertProgress(tenantId, seederKey, SeederStatus.FAILED);
            tenantInfoService.markProvisioningStatus(tenantId, TenantProvisioningStatus.FAILED);
            log.error("Tenant {} provisioning FAILED at seeder {}", tenantId, seederKey);
            return null;
        });
    }

    /**
     * Time-driven fallback (the authoritative FAILED source): flips any tenant stuck in INITIALIZING past
     * {@code readyTimeoutSeconds} to FAILED, so a seed that never completes (real failure / consumer down /
     * MQ stalled) surfaces instead of hanging forever. Driven by tenant-starter's own cron consumer, so it is
     * <b>softa-self-sufficient</b> — it does NOT require the app to opt into a DLQ.
     *
     * <p>Idempotent: {@code markProvisioningStatus} is a no-op at target. Not terminal either — if the seed
     * later completes, {@code markSeederReady} flips the tenant back from FAILED to READY. Anchor =
     * {@code createdTime} (≈ beginProvisioning); {@code TenantInfo} is a shared table, swept in system context.
     *
     * @return number of tenants flipped to FAILED this pass
     */
    public int failTimedOut() {
        return inSystemContext(() -> {
            LocalDateTime cutoff = LocalDateTime.now().minusSeconds(props.getReadyTimeoutSeconds());
            Filters filters = new Filters()
                    .eq(TenantInfo::getProvisioningStatus, TenantProvisioningStatus.INITIALIZING)
                    .lt(TenantInfo::getCreatedTime, cutoff);
            List<TenantInfo> stuck = tenantInfoService.searchList(filters);
            for (TenantInfo tenant : stuck) {
                tenantInfoService.markProvisioningStatus(tenant.getId(), TenantProvisioningStatus.FAILED);
                log.warn("Tenant {} stuck in provisioning INITIALIZING past {}s → FAILED",
                        tenant.getId(), props.getReadyTimeoutSeconds());
            }
            return stuck.size();
        });
    }

    /**
     * Dependency gate for a downstream seeder that must wait for one or more upstream seeders. Returns true
     * once every key in {@code dependsOn} is DONE for this tenant — a set-containment check, so it is
     * <b>order-independent</b> (upstreams may complete in any order) and repeat-safe. {@code dependsOn}
     * empty/null → always satisfied (no dependency). This is the generic form: a single-upstream seeder
     * passes a one-element set; a multi-upstream seeder passes the full set and only proceeds once all are in.
     *
     * <p>{@code justCompletedKey} — the seederKey of the message that triggered the check — is folded into the
     * DONE set before comparing. The coordinator and the downstream consumer both subscribe to
     * {@code seeder-completed} (fan-out), so when the downstream checks off the very message that reported an
     * upstream done, the coordinator may not have upserted that row yet; folding it in closes that race for the
     * triggering upstream. (Several mutually-independent upstreams completing concurrently is not fully covered
     * — but the current graph is a chain (pre-data → corehr) where the last upstream's message arrives after
     * earlier ones are already DONE; downstream seed is idempotent and Pulsar redelivery is the backstop.)
     *
     * @param dependsOn        upstream seeder keys this seeder waits for
     * @param justCompletedKey the seederKey just reported done (may be null)
     */
    public boolean dependenciesSatisfied(Long tenantId, Set<String> dependsOn, String justCompletedKey) {
        if (dependsOn == null || dependsOn.isEmpty()) {
            return true;
        }
        return inSystemContext(() -> {
            Set<String> done = doneKeys(tenantId);
            if (justCompletedKey != null) {
                done.add(justCompletedKey);
            }
            return done.containsAll(dependsOn);
        });
    }

    /** Idempotent upsert of one (tenantId, seederKey) row. Runs inside the caller's system context. */
    private void upsertProgress(Long tenantId, String seederKey, SeederStatus status) {
        Filters filters = new Filters()
                .eq(TenantSeedProgress::getTenantId, tenantId)
                .eq(TenantSeedProgress::getSeederKey, seederKey);
        TenantSeedProgress existing = this.searchOne(filters).orElse(null);
        if (existing == null) {
            TenantSeedProgress row = new TenantSeedProgress();
            row.setTenantId(tenantId);
            row.setSeederKey(seederKey);
            row.setStatus(status);
            this.createOne(row);
        } else if (existing.getStatus() != status) {
            existing.setStatus(status);
            this.updateOne(existing);
        }
    }

    /** The set of seeder keys currently DONE for this tenant. Runs inside the caller's system context. */
    private Set<String> doneKeys(Long tenantId) {
        Filters filters = new Filters()
                .eq(TenantSeedProgress::getTenantId, tenantId)
                .eq(TenantSeedProgress::getStatus, SeederStatus.DONE);
        return this.searchList(filters).stream()
                .map(TenantSeedProgress::getSeederKey)
                .collect(Collectors.toSet());
    }
}
