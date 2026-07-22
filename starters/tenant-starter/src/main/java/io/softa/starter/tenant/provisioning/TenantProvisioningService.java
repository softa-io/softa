package io.softa.starter.tenant.provisioning;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import static io.softa.framework.base.context.ContextUtils.inSystemContext;
import io.softa.framework.base.enums.Timezone;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.tenant.entity.Plan;
import io.softa.starter.tenant.entity.TenantInfo;
import io.softa.starter.tenant.entity.TenantSubscription;
import io.softa.starter.tenant.enums.TenantLifecycle;
import io.softa.starter.tenant.enums.TenantStatus;
import io.softa.starter.tenant.service.TenantSubscriptionService;
import io.softa.starter.tenant.service.impl.TenantInfoServiceImpl;

/**
 * Tenant provisioning — a reusable tenant-starter feature. Creates the tenant registry row + its owned
 * 1:1 {@link TenantSubscription} (the version: planId + lifecycle + effective dates) in a <b>system
 * context</b> (crossTenant + skip-permission; both rows are shared / non-tenant-scoped), then publishes
 * {@link TenantProvisionedEvent} so the app can react.
 *
 * <p><b>Why an event, not direct calls:</b> per-tenant seeding lives in metadata-starter and the first
 * admin lives in user-starter — both are ⊥ to tenant-starter, so tenant-starter must not call them.
 * The app (which depends on all of them) listens for the event and does the seeding / admin creation.
 * The event fires <b>synchronously inside this transaction</b>, so those app-side reactions stay atomic
 * with tenant creation — a listener that throws rolls the whole provisioning back.
 */
@Slf4j
@Service
public class TenantProvisioningService {

    private final TenantInfoServiceImpl tenantInfoService;
    private final TenantSubscriptionService subscriptionService;
    private final ModelService<?> modelService;
    private final ApplicationEventPublisher eventPublisher;

    public TenantProvisioningService(TenantInfoServiceImpl tenantInfoService,
                                     TenantSubscriptionService subscriptionService,
                                     ModelService<?> modelService,
                                     ApplicationEventPublisher eventPublisher) {
        this.tenantInfoService = tenantInfoService;
        this.subscriptionService = subscriptionService;
        this.modelService = modelService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(rollbackFor = Exception.class)
    public ProvisionResult provision(ProvisionTenantRequest request) {
        Assert.notNull(request, "request must not be null");
        Assert.hasText(request.getName(), "name must not be blank");
        String code = normalizeCode(request.getCode(), request.getName());

        TenantSubscription subscription = buildSubscription(request.getSubscriptionId(),
                request.getDefaultTimezone());
        // System context — persist the owned version + the registry row that links it.
        Long tenantId = inSystemContext(() -> {
            Long subscriptionId = subscriptionService.createOne(subscription);

            TenantInfo tenant = new TenantInfo();
            tenant.setName(request.getName().trim());
            tenant.setCode(code);
            tenant.setStatus(TenantStatus.ACTIVE);
            tenant.setActivatedTime(LocalDateTime.now());
            tenant.setDefaultLanguage(request.getDefaultLanguage());
            tenant.setDefaultTimezone(request.getDefaultTimezone());
            tenant.setDefaultCurrency(request.getDefaultCurrency());
            tenant.setDefaultCountry(request.getDefaultCountry());
            tenant.setDataRegion(request.getDataRegion());
            tenant.setSubscriptionId(subscriptionId);
            return tenantInfoService.createOne(tenant);
        });

        // Synchronous, same-transaction: app-side listeners (seed per-tenant data, create first admin)
        // run before commit, so any failure there rolls back tenant creation too.
        eventPublisher.publishEvent(new TenantProvisionedEvent(tenantId, code, request.getName().trim()));

        log.info("Provisioned tenant id={} code={} plan={}", tenantId, code, subscription.getPlanId());
        return new ProvisionResult(tenantId, code);
    }

    /** Build the owned version row, defaulting plan → the catalog's lowest-tier (fallback) plan,
     *  lifecycle → SUBSCRIBED, effectiveFrom → today. A future effectiveFrom (in the tenant's timezone)
     *  parks it as SCHEDULED — the lifecycle job activates it to SUBSCRIBED at that local start date. */
    private TenantSubscription buildSubscription(ProvisionTenantRequest.SubscriptionInput input,
                                                 Timezone tenantTimezone) {
        TenantSubscription sub = new TenantSubscription();
        sub.setPlanId(input != null && input.getPlanId() != null && !input.getPlanId().isBlank()
                ? input.getPlanId() : defaultPlanId());
        TenantLifecycle requested = (input != null && input.getLifecycle() != null)
                ? input.getLifecycle() : TenantLifecycle.SUBSCRIBED;
        if (!requested.isManuallyAssignable()) {
            // Only TRIAL / SUBSCRIBED at birth. SCHEDULED / GRACE_PERIOD / EXPIRED are reached only via
            // the lifecycle job (activate / expire) or a lapse — never set by hand.
            throw new BusinessException("A tenant can only be created as TRIAL or SUBSCRIBED.");
        }
        LocalDate from = (input != null && input.getEffectiveFrom() != null)
                ? input.getEffectiveFrom() : LocalDate.now();
        sub.setEffectiveFrom(from);
        // Future start (tenant-local) → park as SCHEDULED; the job flips it to SUBSCRIBED at effectiveFrom.
        boolean scheduledStart = from.isAfter(LocalDate.now(Timezone.zoneIdOrUtc(tenantTimezone)));
        sub.setLifecycle(scheduledStart ? TenantLifecycle.SCHEDULED : requested);
        if (input != null) {
            sub.setEffectiveTo(input.getEffectiveTo());
        }
        return sub;
    }

    /** Default plan when the caller specifies none = the catalog's floor plan (lowest {@code tier}),
     *  matching {@code EntitlementResolver}'s fallback. Null when no plan is seeded (the required-field
     *  check then rejects the create — seed the plan catalog first). No plan id is hardcoded. */
    private String defaultPlanId() {
        List<Plan> plans = modelService.searchList("Plan", new FlexQuery(new Filters()), Plan.class);
        return plans.stream()
                .filter(p -> p.getTier() != null)
                .min(Comparator.comparingInt(Plan::getTier).thenComparing(Plan::getId))
                .map(Plan::getId)
                .orElse(null);
    }

    /**
     * Reconcile a subscription's lifecycle against its {@code effectiveFrom} after an inline version edit
     * ({@code /TenantInfo/updateOne} carrying {@code subscriptionId}) — <b>both directions</b>, so the edit
     * takes effect on save instead of waiting for the hourly {@code SubscriptionExpiryJob}:
     * <ul>
     *   <li><b>Defer</b> — an <em>active</em> sub whose {@code effectiveFrom} is still in the future
     *       (tenant-local) → {@link TenantLifecycle#SCHEDULED}, the same rule as provisioning
     *       ({@code buildSubscription}), so pushing a start into the future stops taking effect now.</li>
     *   <li><b>Activate</b> — a {@link TenantLifecycle#SCHEDULED} sub whose {@code effectiveFrom} has
     *       arrived (brought forward to today/past, tenant-local) → {@link TenantLifecycle#SUBSCRIBED},
     *       mirroring {@code SubscriptionExpiryJob.activateDue} (the single source of truth for that
     *       transition), so bringing a scheduled start forward activates immediately.</li>
     * </ul>
     * No-op otherwise (typed post-write correction — the ORM cascade already wrote the edited fields;
     * here we only fix the lifecycle). The caller publishes {@code TenantEntitlementChangedEvent} right
     * after, so the reconciled state is applied at once (cache evicted + MQ fan-out).
     */
    public void reconcileScheduledStart(Long tenantId) {
        if (tenantId == null) {
            return;
        }
        TenantInfo tenant = tenantInfoService.getById(tenantId).orElse(null);
        if (tenant == null || tenant.getSubscriptionId() == null) {
            return;
        }
        TenantSubscription sub = subscriptionService.getById(tenant.getSubscriptionId()).orElse(null);
        if (sub == null || sub.getLifecycle() == null || sub.getEffectiveFrom() == null) {
            return;   // no sub / open start → nothing to reconcile
        }
        boolean startInFuture = sub.getEffectiveFrom()
                .isAfter(LocalDate.now(Timezone.zoneIdOrUtc(tenant.getDefaultTimezone())));
        if (sub.getLifecycle().isEntitlementActive() && startInFuture) {
            // Active but the start is still ahead → defer, exactly like provisioning.
            sub.setLifecycle(TenantLifecycle.SCHEDULED);
            subscriptionService.updateOne(sub);
        } else if (sub.getLifecycle() == TenantLifecycle.SCHEDULED && !startInFuture) {
            // Scheduled and the local start has arrived → activate now, mirroring
            // SubscriptionExpiryJob.activateDue (SCHEDULED → SUBSCRIBED) so the edit does not wait for cron.
            sub.setLifecycle(TenantLifecycle.SUBSCRIBED);
            subscriptionService.updateOne(sub);
        }
    }

    /** Use the supplied code when present, else slug the name; lower-kebab, ≤64 chars. */
    private String normalizeCode(String code, String name) {
        String raw = (code != null && !code.isBlank()) ? code : name;
        String slug = raw.trim().toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
        if (slug.length() > 64) {
            slug = slug.substring(0, 64).replaceAll("-+$", "");
        }
        return slug.isBlank() ? "tenant" : slug;
    }
}
