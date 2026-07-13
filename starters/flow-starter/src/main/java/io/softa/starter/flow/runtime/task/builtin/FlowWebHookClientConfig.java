package io.softa.starter.flow.runtime.task.builtin;

import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import io.softa.framework.web.resilience.ResilientRestClientBuilder;

/**
 * Builds the {@code flowWebhookRestClient} bean used by
 * {@link WebHookTaskExecutor} to call external HTTP endpoints from flow tasks.
 * <p>
 * Policies live under {@code resilience4j.{retry,circuitbreaker}.instances.flow-webhook}.
 * Webhooks commonly fan out to many distinct third-party hosts, so per-host
 * circuit breakers keep one tenant's broken webhook from tripping everyone else's.
 */
@Configuration
public class FlowWebHookClientConfig {

    @Bean(name = "flowWebhookRestClient")
    @ConditionalOnMissingBean(name = "flowWebhookRestClient")
    public RestClient flowWebhookRestClient(ResilientRestClientBuilder builder) {
        HttpClientSettings settings = HttpClientSettings.defaults()
                .withConnectTimeout(Duration.ofSeconds(5))
                .withReadTimeout(Duration.ofSeconds(30));
        return builder.name("flow-webhook")
                .httpSettings(settings)
                .perHostCircuitBreaker()
                .build();
    }
}
