package io.softa.framework.web.config;

import jakarta.annotation.Nonnull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.CollectionUtils;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import io.softa.framework.base.config.SystemConfig;

@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Autowired
    private SystemConfig systemConfig;

    @Override
    public void addCorsMappings(@Nonnull CorsRegistry registry) {
        if (!CollectionUtils.isEmpty(systemConfig.getAllowedOrigins())) {
            // Enable CORS for specified origins for all paths
            registry.addMapping("/**")
                    .allowedOrigins(systemConfig.getAllowedOrigins().toArray(new String[0]))
                    .allowedMethods("*")
                    .allowedHeaders("*")
                    .allowCredentials(true);
        }
    }
}