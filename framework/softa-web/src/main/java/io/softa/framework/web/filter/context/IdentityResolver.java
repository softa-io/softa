package io.softa.framework.web.filter.context;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import io.softa.framework.web.enums.IdentifyType;

/**
 * Resolve access identity requirements based on request paths.
 */
@Component
public class IdentityResolver {

    @Value("${server.servlet.context-path:}")
    private String contextPath;

    /**
     * Known-Issues M3: additional patterns (comma-separated) that should
     * be treated as ANONYMOUS at the ContextScopeFilter layer. Framework
     * hardcoded {@link #ANONYMOUS_PATHS} covers login only; deployments
     * that expose extra pre-auth endpoints (health check, tenant probe,
     * etc.) must declare them here so ContextScopeFilter doesn't reject
     * before {@code PermissionInterceptor}'s {@code public-uri-patterns}
     * gate even gets a chance to run.
     *
     * <p>Kept as a comma-separated {@code @Value} string (not a bound
     * {@code @ConfigurationProperties} list) to avoid pulling a new
     * properties class + auto-config wiring into softa-web for a single
     * knob — bootstrap-order simple. Set via yml:
     * <pre>
     * web:
     *   identity:
     *     additional-anonymous-patterns: /healthcheck/**,/tenant-probe/**
     * </pre>
     * or on the command line with a comma-separated value.
     */
    @Value("${web.identity.additional-anonymous-patterns:}")
    private String additionalAnonymousPatternsRaw;

    private static final List<String> EXCLUDE_PATHS = List.of(
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/actuator/**");
    private static final List<String> ANONYMOUS_PATHS = List.of(
            "/login/**",
            // OAuth2 provider browser-redirect endpoint.
            "/MailOauth2/callback");
    private static final List<String> OPENAPI_PATHS = List.of("/openapi/**");
    // /rpc/** is the model RPC between services. It never carries a user session: the caller
    // authenticates with the RPC credentials, checked by RPCController before the body is read.
    private static final List<String> INTERNAL_PATHS = List.of("/internal/**", "/rpc/**");

    // /upgrade/runtime/** is studio→runtime, signature-authenticated; see SignatureConfig.
    private static final List<String> OPERATION_PATHS = List.of("/upgrade/**");

    private static final PathPatternParser PARSER = new PathPatternParser();

    private List<PathPattern> excludePathPatterns;
    private List<PathPattern> anonymousPathPatterns;
    private List<PathPattern> openApiPathPatterns;
    private List<PathPattern> internalPathPatterns;
    private List<PathPattern> operationPathPatterns;

    @PostConstruct
    private void initPatterns() {
        String prefixPath = normalizeContextPath(contextPath);
        excludePathPatterns = parsePatterns(prefixPath, EXCLUDE_PATHS);

        // Union hardcoded ANONYMOUS_PATHS with yml-configured extensions
        // (Known-Issues M3). Blank / missing config → hardcoded list only,
        // matches pre-M3 behaviour.
        List<String> anonymous = new ArrayList<>(ANONYMOUS_PATHS);
        if (additionalAnonymousPatternsRaw != null && !additionalAnonymousPatternsRaw.isBlank()) {
            Arrays.stream(additionalAnonymousPatternsRaw.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .forEach(anonymous::add);
        }
        anonymousPathPatterns = parsePatterns(prefixPath, anonymous);

        openApiPathPatterns = parsePatterns(prefixPath, OPENAPI_PATHS);
        internalPathPatterns = parsePatterns(prefixPath, INTERNAL_PATHS);
        operationPathPatterns = parsePatterns(prefixPath, OPERATION_PATHS);
    }

    private String normalizeContextPath(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        // remove the trailing slash
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        // add the leading slash if not exists
        return path.startsWith("/") ? path : "/" + path;
    }

    private static List<PathPattern> parsePatterns(String prefixPath, List<String> paths) {
        return paths.stream().map(path -> prefixPath + path).map(PARSER::parse).toList();
    }

    private static boolean matchesAny(String path, List<PathPattern> patterns) {
        PathContainer container = PathContainer.parsePath(path);
        for (PathPattern pattern : patterns) {
            if (pattern.matches(container)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get the IdentifyType required for the given path.
     * The priority is:
     * ANONYMOUS > OPENAPI > INTERNAL > USER
     * All paths not matching the above types are considered USER type.
     * @param path the request path
     */
    public IdentifyType getIdentifyRequired(String path) {
        if (matchesAny(path, excludePathPatterns)) {
            return IdentifyType.NONE;
        } else if (matchesAny(path, anonymousPathPatterns)) {
            return IdentifyType.ANONYMOUS;
        } else if (matchesAny(path, openApiPathPatterns)) {
            return IdentifyType.OPENAPI;
        } else if (matchesAny(path, internalPathPatterns)) {
            return IdentifyType.INTERNAL;
        } else if (matchesAny(path, operationPathPatterns)) {
            return IdentifyType.OPERATION;
        } else {
            return IdentifyType.USER;
        }
    }
}
