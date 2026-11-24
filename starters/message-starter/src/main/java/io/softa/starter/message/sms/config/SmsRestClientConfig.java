package io.softa.starter.message.sms.config;

import java.time.Duration;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import io.softa.framework.web.resilience.ResilientRestClientBuilder;
import io.softa.starter.message.config.MessageProperties;
import io.softa.starter.message.sms.support.ConnectFailures;

/**
 * Provides a shared resilient {@link RestClient} bean for all SMS adapters.
 * <p>
 * Connect / read timeouts come from {@code softa.message.sms.transport.*}
 * (see {@link MessageProperties.Transport}). Retry / circuit-breaker policies
 * come from {@code resilience4j.{retry,circuitbreaker}.instances.sms-provider}.
 * Per-host CB prevents one flaky gateway (e.g. Twilio outage) from tripping
 * the breaker for the other adapters that use the same logical instance name.
 * <p>
 * The retry retries only a call that never reached the provider ({@link ConnectFailures}), once, after
 * {@link #CONNECT_RETRY_WAIT}. Sending a text is not idempotent — OneWaySMS even sends it with a GET — so
 * a read timeout, a reset or a 5xx, after which the provider may have sent it, is not repeated here; the
 * send record decides what happens next. The registry's defaults would retry every exception three
 * times. A {@code resilience4j.retry.instances.sms-provider} block in the application's configuration
 * still replaces this one.
 */
@Configuration
public class SmsRestClientConfig {

    static final String INSTANCE_NAME = "sms-provider";

    /** One retry in all: a connect failure that lasts is the send record's to retry, 30 seconds on. */
    static final int MAX_ATTEMPTS = 2;

    static final Duration CONNECT_RETRY_WAIT = Duration.ofMillis(500);

    /** What the client retries unless the application configures the instance itself. */
    static RetryConfig connectFailureRetry() {
        return RetryConfig.custom()
                .maxAttempts(MAX_ATTEMPTS)
                .waitDuration(CONNECT_RETRY_WAIT)
                .retryOnException(ConnectFailures::isConnectFailure)
                .build();
    }

    @Bean
    @ConditionalOnMissingBean(name = "smsRestClient")
    public RestClient smsRestClient(ResilientRestClientBuilder builder, MessageProperties properties,
                                    RetryRegistry retryRegistry) {
        // Registered before the builder looks the instance up; an instance configured in YAML is already
        // in the registry and is kept.
        retryRegistry.retry(INSTANCE_NAME, connectFailureRetry());
        HttpClientSettings settings = HttpClientSettings.defaults()
                .withConnectTimeout(properties.getSms().getTransport().getConnectionTimeout())
                .withReadTimeout(properties.getSms().getTransport().getReadTimeout());
        return builder.name(INSTANCE_NAME)
                .httpSettings(settings)
                .perHostCircuitBreaker()
                .build();
    }
}
