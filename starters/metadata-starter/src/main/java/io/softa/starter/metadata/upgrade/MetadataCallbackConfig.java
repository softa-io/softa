package io.softa.starter.metadata.upgrade;

import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import io.softa.framework.web.resilience.ResilientRestClientBuilder;

/**
 * Builds the {@code metadataCallbackRestClient} bean used by
 * {@link MetadataCallbackClient} to POST completion webhooks back to the studio.
 * <p>
 * Resilience config lives in {@code application.yml} under
 * {@code resilience4j.retry.instances.metadata-callback} and
 * {@code resilience4j.circuitbreaker.instances.metadata-callback}; missing YAML falls
 * back to registry defaults. Per-host circuit breakers keep one unreachable studio
 * from tripping the breaker for other studios talking to this runtime.
 * <p>
 * No signature interceptor is wired: runtime → studio callbacks are authenticated by
 * the one-time {@code callbackToken} header alone, not by the per-env keypair.
 */
@Configuration
public class MetadataCallbackConfig {

    @Bean(name = "metadataCallbackRestClient")
    @ConditionalOnMissingBean(name = "metadataCallbackRestClient")
    public RestClient metadataCallbackRestClient(ResilientRestClientBuilder builder) {
        HttpClientSettings settings = HttpClientSettings.defaults()
                .withConnectTimeout(Duration.ofSeconds(5))
                .withReadTimeout(Duration.ofSeconds(15));
        return builder.name("metadata-callback")
                .httpSettings(settings)
                .perHostCircuitBreaker()
                .build();
    }
}
