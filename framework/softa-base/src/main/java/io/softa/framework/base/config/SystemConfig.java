package io.softa.framework.base.config;

import java.util.List;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "system")
public class SystemConfig {

    private String name;

    private String defaultLanguage;

    private boolean enableAuth;

    private boolean enableChangeLog;

    private boolean enableMultiTenancy;

    private boolean enableInsertId;

    /**
     * Enable log request body in exception handler, default is true
     */
    private boolean enableLogRequestBody = true;

    // Multiple origins are allowed, cannot be set to "*"
    // For example: - http://localhost:3000/
    private List<String> allowedOrigins;

    /**
     * Publicly-reachable base URL (scheme + host + port) of this application, as
     * seen by other systems. Does NOT include the servlet context path — use
     * {@link #getApiRootUrl()} when you need the full API root.
     * <p>
     * Not inferred from the servlet container because the app may sit behind a
     * reverse proxy, public ingress, or a different hostname than it uses locally.
     */
    private String publicAccessUrl;

    /**
     * Pulled from {@code server.servlet.context-path} so {@link #getApiRootUrl()}
     * can compose the full API root. Not a {@code system.*} property — bound via
     * {@code @Value} rather than {@code @ConfigurationProperties}.
     */
    @Value("${server.servlet.context-path:}")
    private String contextPath;

    // Singleton instance
    public static SystemConfig env;

    @PostConstruct
    public void init() {
        env = this;
    }

    /**
     * Externally-visible API root — {@link #getPublicAccessUrl()} concatenated
     * with the servlet context path. Use this (not {@code publicAccessUrl}) when
     * advertising callback / webhook URLs that point back at one of this app's
     * REST endpoints. Returns {@code null} when {@code publicAccessUrl} is unset.
     */
    public String getApiRootUrl() {
        if (publicAccessUrl == null || publicAccessUrl.isBlank()) {
            return null;
        }
        String host = publicAccessUrl.endsWith("/")
                ? publicAccessUrl.substring(0, publicAccessUrl.length() - 1)
                : publicAccessUrl;
        if (contextPath == null || contextPath.isBlank() || "/".equals(contextPath)) {
            return host;
        }
        String path = contextPath.startsWith("/") ? contextPath : "/" + contextPath;
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return host + path;
    }

}
