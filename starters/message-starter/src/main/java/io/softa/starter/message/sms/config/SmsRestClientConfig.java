package io.softa.starter.message.sms.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Provides a shared {@link RestClient} bean for all SMS adapters.
 * <p>
 * Each adapter injects this bean and configures per-request URI, headers,
 * and body via the fluent API. The underlying HTTP client is thread-safe
 * and shared across adapters.
 */
@Configuration
public class SmsRestClientConfig {

    @Bean
    @ConditionalOnMissingBean(name = "smsRestClient")
    public RestClient smsRestClient(RestClient.Builder builder) {
        return builder.build();
    }
}
