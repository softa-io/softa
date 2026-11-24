package io.softa.starter.message.sms.config;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.Test;

import io.softa.framework.web.resilience.ResilientRestClientBuilder;
import io.softa.framework.web.resilience.TransientHttpException;
import io.softa.starter.message.config.MessageProperties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmsRestClientConfigTest {

    private static RetryRegistry buildClientWith(RetryRegistry registry) {
        new SmsRestClientConfig().smsRestClient(
                new ResilientRestClientBuilder(registry, CircuitBreakerRegistry.ofDefaults()),
                new MessageProperties(), registry);
        return registry;
    }

    @Test
    void theClientRetriesAConnectFailureOnceAndNothingElse() {
        RetryConfig config = buildClientWith(RetryRegistry.ofDefaults())
                .retry(SmsRestClientConfig.INSTANCE_NAME).getRetryConfig();

        assertEquals(2, config.getMaxAttempts());
        assertTrue(config.getExceptionPredicate().test(new ConnectException("Connection refused")));
        // The provider may have sent the text after any of these: not repeated by the client.
        assertFalse(config.getExceptionPredicate().test(new SocketTimeoutException("Read timed out")));
        assertFalse(config.getExceptionPredicate().test(
                new TransientHttpException(502, "Bad Gateway", URI.create("https://example.com"))));
        assertFalse(config.getExceptionPredicate().test(
                new TransientHttpException(503, "Service Unavailable", URI.create("https://example.com"))));
    }

    @Test
    void anInstanceTheApplicationConfiguredIsKept() {
        // What resilience4j's Spring Boot integration does for a resilience4j.retry.instances.sms-provider block.
        RetryRegistry registry = RetryRegistry.ofDefaults();
        registry.retry(SmsRestClientConfig.INSTANCE_NAME, RetryConfig.custom().maxAttempts(4).build());

        assertEquals(4, buildClientWith(registry).retry(SmsRestClientConfig.INSTANCE_NAME)
                .getRetryConfig().getMaxAttempts());
    }
}
