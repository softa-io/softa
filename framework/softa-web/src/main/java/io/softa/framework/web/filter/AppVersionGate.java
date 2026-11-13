package io.softa.framework.web.filter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import io.softa.framework.base.enums.ResponseCode;
import io.softa.framework.web.config.AppVersionProperties;
import io.softa.framework.web.enums.IdentifyType;
import io.softa.framework.web.filter.context.IdentityResolver;
import io.softa.framework.web.response.ApiResponseErrorDetails;
import io.softa.framework.web.session.SessionStore;

/**
 * Refuses an APP below the minimum supported version with {@code UPGRADE_REQUIRED}, so an old APP
 * cannot carry on past a skipped launch check.
 *
 * <p>Only signed-in endpoints: the version check itself, sign-in and the other anonymous endpoints
 * stay open, so an old APP can still learn where to upgrade. Runs right after the identity filter.
 *
 * <p>Only requests carrying BOTH APP headers. {@link SessionStore#isAppRequest} calls a request with
 * either one an APP's, which is the right question for how a session travels; here the two are what
 * the check compares — a platform names the floor, a version is held against it — so a request
 * missing either has nothing to compare and passes. Web and H5 send neither and are never refused
 * here. The headers are self-reported: this steers an honest old APP to the upgrade screen, and is
 * no security boundary.
 *
 * <p>Signing out also stays open, as do the paths in
 * {@link AppVersionProperties#getUpgradeExemptPaths()}: an APP stuck on the upgrade screen must still
 * be able to leave.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class AppVersionGate extends OncePerRequestFilter {

    public static final String UPGRADE_REQUIRED = "UPGRADE_REQUIRED";
    private static final String LOGOUT_PATH = "/UserAccount/logout";

    private final AppVersionProperties versions;
    private final IdentityResolver identityResolver;
    private final List<Exempt> exempt;

    public AppVersionGate(AppVersionProperties versions, IdentityResolver identityResolver) {
        this.versions = versions;
        this.identityResolver = identityResolver;
        List<Exempt> paths = new ArrayList<>();
        paths.add(Exempt.parse(LOGOUT_PATH));
        versions.getUpgradeExemptPaths().stream().map(Exempt::parse).forEach(paths::add);
        this.exempt = List.copyOf(paths);
    }

    /** One way out: a path pattern, and the HTTP method it is limited to, or null for any. */
    private record Exempt(String method, PathPattern pattern) {

        private static final PathPatternParser PARSER = new PathPatternParser();

        /** Fails on a malformed entry, so a typo stops the boot rather than trapping an old APP. */
        static Exempt parse(String entry) {
            String[] parts = entry.trim().split("\\s+");
            if (parts.length > 2) {
                throw new IllegalArgumentException("app.upgrade-exempt-paths: expected \"[METHOD] /path\", got: " + entry);
            }
            return parts.length == 2
                    ? new Exempt(parts[0].toUpperCase(), PARSER.parse(parts[1]))
                    : new Exempt(null, PARSER.parse(parts[0]));
        }

        boolean covers(String requestMethod, PathContainer path) {
            return (method == null || method.equalsIgnoreCase(requestMethod)) && pattern.matches(path);
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (this.mustUpgrade(request)) {
            ApiResponseErrorDetails<Void> refusal = ApiResponseErrorDetails
                    .exception(ResponseCode.BUSINESS_EXCEPTION, "This version of the app is no longer supported. Please update.")
                    .withErrorCode(UPGRADE_REQUIRED, null);
            ContextScopeFilter.writeError(response, refusal);
            return;
        }
        chain.doFilter(request, response);
    }

    boolean mustUpgrade(HttpServletRequest request) {
        String platform = request.getHeader(SessionStore.APP_PLATFORM_HEADER);
        String version = request.getHeader(SessionStore.APP_VERSION_HEADER);
        if (StringUtils.isBlank(platform) || StringUtils.isBlank(version)) {
            return false;
        }
        if (identityResolver.getIdentifyRequired(request.getRequestURI()) != IdentifyType.USER
                || isExempt(request)) {
            return false;
        }
        AppVersionProperties.Release release = versions.of(platform);
        return release != null && AppVersionProperties.isOlder(version, release.getMinVersion());
    }

    private boolean isExempt(HttpServletRequest request) {
        PathContainer path = PathContainer.parsePath(
                request.getRequestURI().substring(request.getContextPath().length()));
        return exempt.stream().anyMatch(way -> way.covers(request.getMethod(), path));
    }
}
