package io.softa.starter.user.oauth2;

import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import io.softa.framework.web.resilience.ResilientRestClientBuilder;

/**
 * Builds {@code userOAuthRestClient} — shared by all OAuth2 providers
 * (Google, LinkedIn, TikTok, X).
 * <p>
 * Policies come from {@code resilience4j.{retry,circuitbreaker}.instances.user-oauth}.
 * Per-host circuit breakers ensure one flaky upstream (e.g. Twitter) doesn't block
 * login via a healthy one (e.g. Google).
 */
@Configuration
public class UserOAuthClientConfig {

    @Bean(name = "userOAuthRestClient")
    @ConditionalOnMissingBean(name = "userOAuthRestClient")
    public RestClient userOAuthRestClient(ResilientRestClientBuilder builder) {
        HttpClientSettings settings = HttpClientSettings.defaults()
                .withConnectTimeout(Duration.ofSeconds(5))
                .withReadTimeout(Duration.ofSeconds(15));
        return builder.name("user-oauth")
                .httpSettings(settings)
                .perHostCircuitBreaker()
                .build();
    }
}
