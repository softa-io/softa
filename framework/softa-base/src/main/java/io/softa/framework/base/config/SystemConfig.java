package io.softa.framework.base.config;

import java.util.List;
import jakarta.annotation.PostConstruct;
import lombok.Data;
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

    // Singleton instance
    public static SystemConfig env;

    @PostConstruct
    public void init() {
        env = this;
    }

}
