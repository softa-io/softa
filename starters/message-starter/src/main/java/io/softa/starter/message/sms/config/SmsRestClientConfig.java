package io.softa.starter.message.sms.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import io.softa.framework.web.resilience.ResilientRestClientBuilder;
import io.softa.starter.message.config.MessageProperties;

/**
 * Provides a shared resilient {@link RestClient} bean for all SMS adapters.
 * <p>
 * Connect / read timeouts come from {@code softa.message.sms.transport.*}
 * (see {@link MessageProperties.Transport}). Retry / circuit-breaker policies
 * come from {@code resilience4j.{retry,circuitbreaker}.instances.sms-provider}.
 * Per-host CB prevents one flaky gateway (e.g. Twilio outage) from tripping
 * the breaker for the other adapters that use the same logical instance name.
 */
@Configuration
public class SmsRestClientConfig {

    @Bean
    @ConditionalOnMissingBean(name = "smsRestClient")
    public RestClient smsRestClient(ResilientRestClientBuilder builder, MessageProperties properties) {
        HttpClientSettings settings = HttpClientSettings.defaults()
                .withConnectTimeout(properties.getSms().getTransport().getConnectionTimeout())
                .withReadTimeout(properties.getSms().getTransport().getReadTimeout());
        return builder.name("sms-provider")
                .httpSettings(settings)
                .perHostCircuitBreaker()
                .build();
    }
}
