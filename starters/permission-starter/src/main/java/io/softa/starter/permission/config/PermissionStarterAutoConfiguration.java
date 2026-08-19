package io.softa.starter.permission.config;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;

import io.softa.framework.orm.service.CacheService;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.service.PermissionService;
import io.softa.starter.permission.spi.PermissionEndpointSource;
import io.softa.starter.permission.spi.PermissionSnapshotProvider;
import io.softa.starter.permission.spi.SensitiveFieldSetSource;
import io.softa.starter.permission.spi.support.DbPermissionEndpointSource;
import io.softa.starter.permission.spi.support.DbSensitiveFieldSetSource;
import io.softa.starter.permission.spi.support.DefaultPermissionSnapshotProvider;
import io.softa.starter.permission.index.EndpointIndex;
import io.softa.starter.permission.scope.ScopeApplicabilityResolver;
import io.softa.starter.permission.scope.ScopeRuleCompiler;
import io.softa.starter.permission.sensitive.SensitiveFieldSetCache;
import io.softa.starter.permission.service.PermissionServiceImpl;

/**
 * Permission enforce starter (2026-07-14 三合一, 2026-07-15 SPI 倒置落地).
 *
 * <p>Split out of {@code user-starter}: the route-admission interceptor +
 * scope engine ({@code ScopeRuleCompiler} + {@code ScopeContributor} SPI +
 * generic contributors) + {@code EndpointIndex} + {@code SensitiveFieldSetCache}
 * + {@code PermissionServiceImpl}. All three RBAC data feeds are inverted to
 * {@code softa-orm} SPIs — {@link PermissionSnapshotProvider} (per-user
 * snapshot), {@code PermissionEndpointSource} (endpoint→permission rows),
 * {@code SensitiveFieldSetSource} (sensitive-field-set defs) — so this starter
 * depends only on {@code softa-orm}/{@code softa-web}, <b>never</b> on
 * {@code user-starter}. A business microservice can enforce without bundling
 * login/RBAC; {@code user-starter} provides the SPI impls.
 *
 * <p>Scans {@code io.softa.starter.permission} so consumers get the
 * interceptor + engine beans by depending on the jar alone.
 */
@AutoConfiguration
@ComponentScan("io.softa.starter.permission")
public class PermissionStarterAutoConfiguration {

    /**
     * Default data-plane enforcement impl. {@code @ConditionalOnMissingBean}
     * lets an app opt out with its own {@link PermissionService} (e.g. the
     * mini / demo no-op stubs win over this default). Declared as a {@code @Bean}
     * (not a {@code @Component}) precisely so the condition is evaluated in
     * auto-configuration order — after the app's own beans are registered.
     *
     * <p>{@code @Lazy} on the snapshot / cache / model deps breaks the init
     * cycle (PermissionServiceImpl ← ModelServiceImpl ← … ← the snapshot
     * provider ← this): every dep is used per-request, never during wiring.
     */
    @Bean
    @ConditionalOnMissingBean(PermissionService.class)
    public PermissionService permissionService(
            @Lazy PermissionSnapshotProvider snapshotProvider,
            ScopeRuleCompiler scopeCompiler,
            @Lazy SensitiveFieldSetCache sfsCache,
            @Lazy ModelService<?> modelService,
            @Lazy ScopeApplicabilityResolver applicability,
            // ObjectProvider passed as a Supplier, resolved lazily: calling getIfAvailable() HERE
            // would build EndpointIndex during this bean's construction — before AppStartup loads
            // ModelManager — leaving the index empty. Deferring the resolve to first use keeps the
            // index's construction after the catalog is ready. The index is genuinely optional; a
            // deployment without it answers "granted" for the file endpoints.
            ObjectProvider<EndpointIndex> endpointIndex) {
        return new PermissionServiceImpl(snapshotProvider, scopeCompiler, sfsCache, modelService, applicability,
                endpointIndex::getIfAvailable);
    }

    /**
     * Standalone fallback SPIs (2026-07-16). Let permission-starter boot with NO
     * {@code user-starter} on the classpath (pure-enforce microservice): the
     * per-user snapshot is read from Redis, and the endpoint / sensitive-field-set
     * defs from the DB by model name (约定读). Each is
     * {@code @ConditionalOnMissingBean} so in the monolith {@code user-starter}'s
     * richer impls win — these never register there. {@code user-starter}'s
     * {@code @AutoConfigureBefore} on this class guarantees its impls are
     * registered before these conditions evaluate (no ambiguous double bean).
     *
     * <p>{@code @Lazy ModelService} breaks the boot cycle
     * (ModelService ← PermissionService ← caches ← these DB sources); the sources
     * are only invoked at the caches' {@code @PostConstruct}, never during wiring.
     */
    @Bean
    @ConditionalOnMissingBean(PermissionSnapshotProvider.class)
    public PermissionSnapshotProvider permissionSnapshotProvider(
            CacheService cacheService,
            @Lazy ModelService<?> modelService,
            @Lazy SensitiveFieldSetCache sensitiveFieldSetCache,
            ObjectProvider<ScopeRuleCompiler> scopeRuleCompiler,
            @Value("${permission.platform-nav-prefixes:}") String platformNavPrefixes) {
        // Default: build the per-user snapshot from the standard RBAC config models
        // (约定读 into view DTOs). A pure-enforce deployment without those models
        // gets fail-closed nulls and should register its own provider (e.g. a
        // RedisPermissionSnapshotProvider keep-warm reader, or an RPC re-sourcer).
        //
        // The compiler is passed as a supplier, not @Lazy like the two above: it reaches ModelService
        // through ScopeApplicabilityResolver — the same cycle — but @Lazy proxies its target and
        // ScopeRuleCompiler is final, so the proxy cannot be built. getIfAvailable defers resolution to
        // first use without one, and lets a pure-enforce context that registers no compiler start
        // instead of failing at wiring.
        return new DefaultPermissionSnapshotProvider(cacheService, modelService, sensitiveFieldSetCache,
                scopeRuleCompiler::getIfAvailable, splitCsv(platformNavPrefixes));
    }

    /** Split a comma-separated config value into a trimmed, non-empty list. */
    private static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    @Bean
    @ConditionalOnMissingBean(PermissionEndpointSource.class)
    public PermissionEndpointSource permissionEndpointSource(@Lazy ModelService<?> modelService) {
        return new DbPermissionEndpointSource(modelService);
    }

    @Bean
    @ConditionalOnMissingBean(SensitiveFieldSetSource.class)
    public SensitiveFieldSetSource sensitiveFieldSetSource(@Lazy ModelService<?> modelService) {
        return new DbSensitiveFieldSetSource(modelService);
    }
}
