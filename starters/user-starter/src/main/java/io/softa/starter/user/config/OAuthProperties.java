package io.softa.starter.user.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * OAuth social login properties
 */
@Data
@Component
@ConfigurationProperties(prefix = "social-oauth")
public class OAuthProperties {

    /**
     * Apple OAuth configuration
     */
    private OAuthConfig apple = new OAuthConfig();

    /**
     * Google OAuth configuration
     */
    private OAuthConfig google = new OAuthConfig();

    /**
     * TikTok OAuth configuration
     */
    private OAuthConfig tiktok = new OAuthConfig();

    /**
     * X (Twitter) OAuth configuration
     */
    private OAuthConfig x = new OAuthConfig();

    /**
     * LinkedIn OAuth configuration
     */
    private OAuthConfig linkedin = new OAuthConfig();

    /**
     * OAuth configuration class
     */
    @Data
    public static class OAuthConfig {

        /**
         * Enable flag
         */
        private boolean enable = false;

        /**
         * Client ID
         */
        private String clientId;

        /**
         * Client Secret
         */
        private String clientSecret;
    }

}