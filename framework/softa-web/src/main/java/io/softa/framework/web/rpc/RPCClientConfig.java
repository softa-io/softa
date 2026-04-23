package io.softa.framework.web.rpc;

import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import io.softa.framework.web.resilience.ResilientRestClientBuilder;

/**
 * Builds the {@code rpcRestClient} bean used by
 * {@link RPCClientImpl} for internal service-to-service RPC.
 * <p>
 * Retry / circuit-breaker policies are configured via Resilience4j YAML under
 * {@code resilience4j.retry.instances.softa-rpc} and
 * {@code resilience4j.circuitbreaker.instances.softa-rpc}.
 */
@Configuration
public class RPCClientConfig {

    @Bean(name = "rpcRestClient")
    @ConditionalOnMissingBean(name = "rpcRestClient")
    public RestClient rpcRestClient(ResilientRestClientBuilder builder) {
        HttpClientSettings settings = HttpClientSettings.defaults()
                .withConnectTimeout(Duration.ofSeconds(3))
                .withReadTimeout(Duration.ofSeconds(30));
        return builder.name("softa-rpc")
                .httpSettings(settings)
                .perHostCircuitBreaker()
                .build();
    }
}
