package io.softa.starter.permission.index;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import io.softa.starter.permission.interceptor.PermissionInterceptorProperties;

/**
 * Startup coverage check — permission design §3.8 rule ①: every Spring MVC
 * handler URL must be covered by some permission in the {@link EndpointIndex}
 * (or whitelisted as public / authenticated-bypass). Without it an admin can
 * ship a controller whose endpoint nothing can call — or worse, one that
 * anyone can call because no permission gates it.
 *
 * <p>Split out of {@code user-starter}'s {@code PermissionRegistryValidator}
 * (2026-07-17 full ⊥ decouple). This one rule is pure <b>engine</b> domain: it
 * cross-checks the {@link EndpointIndex} (a permission-starter concept) against
 * the live MVC handler set, needs no user / RBAC-structure knowledge, and so
 * belongs with the engine. The RBAC-structure rules (②–⑩ — NavigationType tree,
 * FK / model integrity) stay in {@code user-starter}, which no longer imports
 * anything from this starter.
 *
 * <p>Runs at {@link ApplicationReadyEvent}: {@link EndpointIndex#init()} is
 * {@code @PostConstruct}, so the index is fully built by the time this fires.
 *
 * <p><b>Log-only, never fail-fast</b> (mirrors the origin validator's decision):
 * in a multi-app world the same seed ships endpoints for modules that may or may
 * not be on a given app's classpath, so taking the app down at boot is the wrong
 * trade-off. Ops sees the full list in the log.
 *
 * <p><b>WARN, not ERROR.</b> An uncovered endpoint is unreachable, not open: the
 * interceptor refuses a request whose URI the index does not hold — to every caller
 * but the platform administrator — so "nothing grants it" reads as "nobody in a
 * tenant can call it". Most of the list is that way on
 * purpose — the generic model controller serves every verb for every model, and
 * only what a page declares is ever registered, so each model with no page of its
 * own contributes a screenful. Reporting a by-design dead route at the level
 * reserved for things that are broken buries the one entry that is: a handler
 * somebody wrote and then could not reach. Level matched to what the finding is.
 *
 * <p>Skips itself when {@code handlerMappings} is empty (starter consumed outside
 * a web context).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EndpointCoverageValidator {

    private final EndpointIndex endpointIndex;
    private final PermissionInterceptorProperties bypassProperties;
    /** Every {@code RequestMappingHandlerMapping} bean — the main MVC mapping
     *  plus any extras (notably the actuator's {@code controllerEndpointHandlerMapping}).
     *  Declared as {@code List} (not {@code ObjectProvider} / bare type) so the
     *  2-bean actuator case doesn't trip Boot's startup bean-uniqueness checker;
     *  {@code List<...>} injection means "every bean of this type". The list is
     *  empty outside a web app and the check skips itself then. */
    private final List<RequestMappingHandlerMapping> handlerMappings;

    private static final AntPathMatcher URI_MATCHER = new AntPathMatcher();

    @EventListener(ApplicationReadyEvent.class)
    public void validate() {
        Map<RequestMappingInfo, HandlerMethod> handlers = collectHandlers();
        if (handlers.isEmpty()) {
            log.debug("EndpointCoverageValidator — no RequestMappingHandlerMapping handlers; skipping (non-web context)");
            return;
        }

        Set<String> uncovered = findUncoveredEndpoints(handlers);
        if (uncovered.isEmpty()) {
            log.info("EndpointCoverageValidator — OK ({} handler mapping(s) checked)", handlers.size());
            return;
        }
        log.warn("EndpointCoverageValidator — {} endpoint(s) not covered by any permission "
                + "(unreachable inside a tenant, not open — the interceptor refuses what the index does not hold; only the platform admin passes):",
                uncovered.size());
        uncovered.forEach(e -> log.warn(
                "  - {} — add to permission.endpoints or public/authenticated-bypass yml", e));
        log.warn("EndpointCoverageValidator — startup continues; a route listed above can be reached by the platform admin only, so fix the seed / classpath only where that is not the intent");
    }

    /** Merge the handler map from EVERY {@code RequestMappingHandlerMapping} bean
     *  — the main MVC mapping plus any extras (notably the actuator's
     *  {@code controllerEndpointHandlerMapping}). The yml bypass lists (e.g.
     *  {@code /actuator/**}) exempt actuator routes from the coverage check. */
    private Map<RequestMappingInfo, HandlerMethod> collectHandlers() {
        Map<RequestMappingInfo, HandlerMethod> handlers = new HashMap<>();
        for (RequestMappingHandlerMapping m : handlerMappings) {
            Map<RequestMappingInfo, HandlerMethod> sub = m.getHandlerMethods();
            if (sub != null) handlers.putAll(sub);
        }
        return handlers;
    }

    /**
     * The set of {@code "METHOD /uri"} endpoints reachable via Spring MVC but
     * not covered by any permission in the {@link EndpointIndex}, after removing
     * public / authenticated-bypass patterns and framework-infra paths.
     *
     * <p>Package-private (not the {@code @EventListener}) so the pure coverage
     * logic is unit-testable without a live application context or log capture.
     */
    Set<String> findUncoveredEndpoints(Map<RequestMappingInfo, HandlerMethod> handlers) {
        List<String> publicPatterns = bypassProperties.getPublicUriPatterns();
        List<String> bypassPatterns = bypassProperties.getAuthenticatedBypassPatterns();
        // Platform-only endpoints are declared, just in a third list. Leaving it out reported every
        // one of them as uncovered, which is the opposite of what the list says: they ARE gated, by
        // a rule the index does not hold. The noise is the damage — this check exists so a genuinely
        // ungated endpoint stands out, and a boot that logs dozens of ERRORs nobody can act on
        // teaches the reader to scroll past the one that matters.
        List<String> platformOnlyPatterns = bypassProperties.getPlatformOnlyPatterns();
        Set<String> uncovered = new HashSet<>();

        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlers.entrySet()) {
            RequestMappingInfo info = entry.getKey();
            Set<String> patterns = info.getPathPatternsCondition() == null
                    ? Set.of()
                    : info.getPathPatternsCondition().getPatternValues();
            if (patterns.isEmpty()) continue;
            // A mapping with no explicit method condition actually serves EVERY
            // verb, so probe them all — otherwise an uncovered PUT/DELETE/PATCH
            // route silently escapes the check (it would still fail-closed at
            // runtime, but the startup coverage signal is the point).
            Set<HttpMethod> methods = info.getMethodsCondition().getMethods().isEmpty()
                    ? Set.of(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT,
                            HttpMethod.PATCH, HttpMethod.DELETE)
                    : info.getMethodsCondition().getMethods().stream()
                            .map(rm -> HttpMethod.valueOf(rm.name()))
                            .collect(java.util.stream.Collectors.toSet());

            for (String uri : patterns) {
                if (isInBypass(uri, publicPatterns)
                        || isInBypass(uri, bypassPatterns)
                        || isInBypass(uri, platformOnlyPatterns)) continue;
                if (isFrameworkInfraPath(uri)) continue;
                for (HttpMethod method : methods) {
                    Set<String> perms = endpointIndex.lookup(uri, method.name());
                    if (perms == null || perms.isEmpty()) {
                        uncovered.add(method.name() + " " + uri);
                    }
                }
            }
        }
        return uncovered;
    }

    private static boolean isInBypass(String uri, List<String> patterns) {
        if (patterns == null) return false;
        for (String pattern : patterns) {
            if (pattern == null || pattern.isEmpty()) continue;
            if (URI_MATCHER.match(pattern, uri)) return true;
        }
        return false;
    }

    /** Framework / infra paths the interceptor's {@code excludePathPatterns}
     *  already skips — mirror that list here so the check doesn't drown ops in
     *  noise about {@code /error} / actuator / swagger. */
    private static boolean isFrameworkInfraPath(String uri) {
        return uri.equals("/error")
                || uri.startsWith("/actuator/")
                || uri.startsWith("/swagger-ui/")
                // springdoc serves the group paths under /v3/api-docs/, and the root document at
                // /v3/api-docs plus /v3/api-docs.yaml — the prefix alone misses the latter two.
                || uri.startsWith("/v3/api-docs")
                || uri.equals("/favicon.ico");
    }
}
