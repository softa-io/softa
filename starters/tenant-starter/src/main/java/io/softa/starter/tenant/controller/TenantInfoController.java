package io.softa.starter.tenant.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.enums.ConvertType;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.utils.IdUtils;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.tenant.entity.TenantInfo;
import io.softa.starter.tenant.provisioning.PlatformSeedGuard;
import io.softa.starter.tenant.provisioning.ProvisionTenantRequest;
import io.softa.starter.tenant.provisioning.TenantProvisioningService;
import io.softa.starter.tenant.provisioning.TenantSeedPurgeService;
import io.softa.starter.tenant.service.SubscriptionPeriodPatch;
import io.softa.starter.tenant.service.TenantSubscriptionPeriodService;
import io.softa.starter.tenant.service.impl.TenantInfoServiceImpl;

/**
 * Shadows the generic {@code /{modelName}} write endpoints for TenantInfo (Spring routes the literal
 * path over the templated {@code ModelController} mapping by specificity).
 * <ul>
 *   <li>{@code createOne} — tenant creation must provision (registry row + owned version + seed),
 *       not bare-insert; delegates to {@link TenantProvisioningService}.</li>
 *   <li>{@code updateOne} / {@code updateOneAndFetch} — the owned 1:1 subscription is rendered inline on
 *       the Tenant Info form, so its {@code periods} relation arrives nested in the payload. It is lifted
 *       out and replayed through {@link TenantSubscriptionPeriodService}, which is what applies the write
 *       guards and refreshes the projection; letting the framework's nested-relation pipeline persist it
 *       would skip both. The rest of the nested object is dropped — every column on it is projected.</li>
 * </ul>
 * Platform-only: {@code /TenantInfo/**} is in {@code permission.platform-only-patterns}.
 */
@Tag(name = "Tenant Provisioning")
@RestController
@RequestMapping("/TenantInfo")
public class TenantInfoController {

    private static final String MODEL = "TenantInfo";
    /** The owned 1:1 subscription relation, as it appears nested in a form payload. */
    private static final String VERSION_FIELD = "subscriptionId";
    /** The subscription's period relation — the one part of that nested object this endpoint accepts. */
    private static final String PERIODS_FIELD = "periods";

    private final TenantProvisioningService provisioningService;
    private final TenantInfoServiceImpl tenantInfoService;
    private final ModelService<Long> modelService;
    private final TenantSubscriptionPeriodService periodService;
    private final TenantSeedPurgeService purgeService;
    private final PlatformSeedGuard platformSeedGuard;

    public TenantInfoController(TenantProvisioningService provisioningService,
                               TenantInfoServiceImpl tenantInfoService,
                               ModelService<Long> modelService,
                               TenantSubscriptionPeriodService periodService,
                               TenantSeedPurgeService purgeService,
                               PlatformSeedGuard platformSeedGuard) {
        this.provisioningService = provisioningService;
        this.tenantInfoService = tenantInfoService;
        this.modelService = modelService;
        this.periodService = periodService;
        this.purgeService = purgeService;
        this.platformSeedGuard = platformSeedGuard;
    }

    // ─── Operational status: the only sanctioned way to change it ───
    // Each of these stamps the matching timestamp, clears the other two, and evicts the tenant caches.
    // Editing the status column through the generic updateOne would skip the eviction, so the login gate
    // would keep reading the cached old value and a suspension would not take effect — which is why the
    // field is rendered read-only in the UI and changed only here.

    @Operation(summary = "Suspend a tenant — blocks login at once and forces its users to re-login")
    @PostMapping("/deactivate")
    public ApiResponse<Boolean> deactivate(@RequestParam Long id) {
        tenantInfoService.deactivate(id);
        return ApiResponse.success(true);
    }

    @Operation(summary = "Reactivate a suspended tenant")
    @PostMapping("/activate")
    public ApiResponse<Boolean> activate(@RequestParam Long id) {
        tenantInfoService.activate(id);
        return ApiResponse.success(true);
    }

    @Operation(summary = "Close a tenant — terminal; data is retained")
    @PostMapping("/close")
    public ApiResponse<Boolean> close(@RequestParam Long id) {
        tenantInfoService.close(id);
        return ApiResponse.success(true);
    }

    @Operation(summary = "Create a tenant (registry row + owned version + per-tenant seed)")
    @PostMapping("/createOne")
    public ApiResponse<Long> createOne(@RequestBody ProvisionTenantRequest request) {
        platformSeedGuard.requireInStep();
        return ApiResponse.success(provisioningService.provision(request).tenantId());
    }

    /**
     * Discard a stuck or failed tenant's seed data and set it up again.
     *
     * <p>Only reachable for a tenant that is still initializing or whose initialization failed — the service
     * enforces that, not this endpoint, because the safety of deleting the rows depends on it. The tenant keeps
     * its id, code, name and the subscription periods ops recorded; everything the seeders wrote is discarded
     * and produced again.
     *
     * <p>Returns what was deleted, per model. A destructive operation that reports nothing about what it
     * removed is one nobody can safely trust or audit.
     */
    @Operation(summary = "Rebuild a tenant whose setup failed — discards its seed data and seeds it again")
    @PostMapping("/rebuild")
    public ApiResponse<Map<String, Integer>> rebuild(@RequestParam Long id) {
        platformSeedGuard.requireInStep();
        return ApiResponse.success(purgeService.rebuild(id));
    }

    // One transaction per submit: a period patch that trips a guard must not leave the tenant's own edits
    // committed alongside an unchanged period list, which is what ops would then be looking at.

    @Operation(summary = "Update a tenant — nested subscription periods go through their guarded service")
    @PostMapping("/updateOne")
    @Transactional(rollbackFor = Exception.class)
    public ApiResponse<Boolean> updateOne(@RequestBody TenantUpdateRequest request) {
        Map<String, Object> row = request.getRow();
        Assert.notEmpty(row, "The data to be updated cannot be empty!");
        Assert.notNull(row.get("id"), "`id` cannot be null or missing when updating data!");
        Object tenantId = row.get("id");
        IdUtils.formatMapId(MODEL, row);
        boolean ok = modelService.updateOne(MODEL, row);
        // After the tenant update: a timezone change in the same submit decides what "today" means for a
        // period recorded without a start date.
        applyPeriodPatch(tenantId, request.periodPatch());
        return ApiResponse.success(ok);
    }

    @Operation(summary = "Update a tenant and fetch — nested subscription periods go through their guarded service")
    @PostMapping("/updateOneAndFetch")
    @Transactional(rollbackFor = Exception.class)
    public ApiResponse<Map<String, Object>> updateOneAndFetch(@RequestBody TenantUpdateRequest request) {
        Map<String, Object> row = request.getRow();
        Assert.notEmpty(row, "The data to be updated cannot be empty!");
        Assert.notNull(row.get("id"), "`id` cannot be null or missing when updating data!");
        Object tenantId = row.get("id");
        IdUtils.formatMapId(MODEL, row);
        Map<String, Object> result = modelService.updateOneAndFetch(MODEL, row, ConvertType.REFERENCE);
        applyPeriodPatch(tenantId, request.periodPatch());
        return ApiResponse.success(result);
    }

    /**
     * The tenant update payload, split by Jackson rather than by hand.
     *
     * <p>{@code subscriptionId} is declared, so it binds to the typed field and — being a known property —
     * never reaches {@code @JsonAnySetter}. Everything else lands in {@link #row}, which is what the generic
     * update receives. That split does the two jobs this endpoint owes:
     *
     * <p>The periods patch is intercepted. Left in the payload, the framework's nested-relation pipeline
     * would persist it through the generic {@code ModelService}, which runs none of the period write guards
     * and leaves the projection stale; it is replayed through the guarded service instead.
     *
     * <p>The rest of the nested object is dropped. Every column on {@code TenantSubscription} is projected,
     * with the refresh logic as its only legitimate writer. The form renders those fields read-only and so
     * echoes back what it read — harmless today, but the same path would cascade a hand-crafted payload
     * straight onto the row authorization reads.
     *
     * <p>Binding it here rather than converting a {@code Map} through the mapper's tree API is deliberate:
     * {@code JsonMapper.treeToValue} blew up at runtime with {@code NoSuchMethodError} — the deployed
     * softa-base and the deployed Jackson disagree on that signature, and a request-body binding does not
     * depend on it.
     */
    @Data
    public static class TenantUpdateRequest {

        /** The owned 1:1 subscription as the form posts it; only its {@code periods} relation is accepted. */
        private ProvisionTenantRequest.SubscriptionInput subscriptionId;

        /** Every other property, i.e. the tenant's own columns. Mutable — {@code formatMapId} rewrites it. */
        private final Map<String, Object> row = new LinkedHashMap<>();

        @JsonAnySetter
        public void putTenantField(String name, Object value) {
            row.put(name, value);
        }

        /** The periods patch, or null when the payload carried no subscription / no periods. */
        public SubscriptionPeriodPatch periodPatch() {
            return subscriptionId == null ? null : subscriptionId.getPeriods();
        }
    }

    /** Applies the patch against the tenant's own subscription — the owner is never taken from the payload. */
    private void applyPeriodPatch(Object tenantIdValue, SubscriptionPeriodPatch patch) {
        if (patch == null) {
            return;
        }
        Long tenantId = IdUtils.convertIdToLong(tenantIdValue);
        TenantInfo tenant = tenantId == null ? null : tenantInfoService.getById(tenantId).orElse(null);
        Assert.notNull(tenant == null ? null : tenant.getSubscriptionId(),
                "This tenant has no subscription row, so it cannot have periods.");
        periodService.applyPatch(tenant.getSubscriptionId(), patch);
    }
}
