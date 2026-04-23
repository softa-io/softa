package io.softa.framework.web.resilience;

import java.net.URI;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Builds {@link RestClient} instances that are transparently wrapped with
 * Resilience4j {@code Retry} and {@code CircuitBreaker} policies.
 * <p>
 * The Retry and CircuitBreaker instances are looked up on the auto-configured
 * {@link RetryRegistry} / {@link CircuitBreakerRegistry} by the caller-supplied
 * {@code instanceName}. YAML configuration lives under
 * {@code resilience4j.retry.instances.<name>} and
 * {@code resilience4j.circuitbreaker.instances.<name>}; missing instances fall back
 * to registry defaults so callers never need to declare YAML to get a usable client.
 * <p>
 * Usage:
 * <pre>{@code
 * @Bean
 * RestClient studioRemoteRestClient(ResilientRestClientBuilder b, StudioRemoteClientProperties p) {
 *     return b.name("studio-remote")
 *             .httpSettings(HttpClientSettings.defaults()
 *                     .withConnectTimeout(p.getConnectTimeout())
 *                     .withReadTimeout(p.getReadTimeout()))
 *             .perHostCircuitBreaker()  // each upstream host gets its own CB
 *             .build();
 * }
 * }</pre>
 * <p>
 * A typical {@code application.yml}:
 * <pre>{@code
 * resilience4j:
 *   retry:
 *     instances:
 *       studio-remote:
 *         max-attempts: 3
 *         wait-duration: 500ms
 *         enable-exponential-backoff: true
 *         exponential-backoff-multiplier: 2
 *         exponential-max-wait-duration: 5s
 *         retry-exceptions:
 *           - io.softa.framework.web.rpc.resilience.TransientHttpException
 *           - java.io.IOException
 *   circuitbreaker:
 *     instances:
 *       studio-remote:
 *         sliding-window-size: 20
 *         failure-rate-threshold: 50
 *         wait-duration-in-open-state: 30s
 * }</pre>
 */
@Component
public class ResilientRestClientBuilder {

    /**
     * Default set of HTTP status codes treated as transient failures — 5xx server
     * errors plus the two 4xx codes that signal "retry may help"
     * ({@code 408 Request Timeout}, {@code 429 Too Many Requests}).
     */
    public static final Set<Integer> DEFAULT_RETRYABLE_STATUSES = Set.of(
            HttpStatus.INTERNAL_SERVER_ERROR.value(),
            HttpStatus.BAD_GATEWAY.value(),
            HttpStatus.SERVICE_UNAVAILABLE.value(),
            HttpStatus.GATEWAY_TIMEOUT.value(),
            HttpStatus.REQUEST_TIMEOUT.value(),
            HttpStatus.TOO_MANY_REQUESTS.value()
    );

    private final RetryRegistry retryRegistry;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public ResilientRestClientBuilder(RetryRegistry retryRegistry,
                                      CircuitBreakerRegistry circuitBreakerRegistry) {
        this.retryRegistry = retryRegistry;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    /**
     * Start building a resilient {@link RestClient} for the given Resilience4j
     * instance name (shared Retry + CircuitBreaker configuration key).
     */
    public Spec name(String instanceName) {
        return new Spec(instanceName, retryRegistry, circuitBreakerRegistry);
    }

    /**
     * Fluent specification. Returned by {@link ResilientRestClientBuilder#name(String)}
     * so chaining {@code .httpSettings(...).build()} reads naturally.
     */
    public static final class Spec {

        private final String instanceName;
        private final RetryRegistry retryRegistry;
        private final CircuitBreakerRegistry circuitBreakerRegistry;

        private HttpClientSettings httpSettings;
        private boolean perHostCircuitBreaker = false;
        private Set<Integer> retryableStatuses = DEFAULT_RETRYABLE_STATUSES;
        private Consumer<RestClient.Builder> customizer;

        private Spec(String instanceName,
                     RetryRegistry retryRegistry,
                     CircuitBreakerRegistry circuitBreakerRegistry) {
            this.instanceName = instanceName;
            this.retryRegistry = retryRegistry;
            this.circuitBreakerRegistry = circuitBreakerRegistry;
        }

        /** Attach timeouts / SSL / redirect policy to the underlying request factory. */
        public Spec httpSettings(HttpClientSettings settings) {
            this.httpSettings = settings;
            return this;
        }

        /**
         * One {@link CircuitBreaker} per {@code URI.getHost()} instead of one shared
         * breaker for the whole instance. Useful when a single logical "service"
         * maps to many upstream hosts (e.g. studio → multiple runtime envs) and you
         * don't want one dead host to trip the breaker for the rest.
         * <p>
         * Per-host breakers inherit the YAML config of the base instance via
         * {@link CircuitBreakerRegistry#circuitBreaker(String, CircuitBreakerConfig)}.
         */
        public Spec perHostCircuitBreaker() {
            this.perHostCircuitBreaker = true;
            return this;
        }

        /**
         * Override the HTTP statuses that should be treated as transient. Defaults to
         * {@link ResilientRestClientBuilder#DEFAULT_RETRYABLE_STATUSES}.
         */
        public Spec retryableStatuses(Set<Integer> statuses) {
            this.retryableStatuses = Set.copyOf(statuses);
            return this;
        }

        /** Append extra retryable statuses without replacing the default set. */
        public Spec addRetryableStatus(int... statuses) {
            Set<Integer> merged = new HashSet<>(this.retryableStatuses);
            for (int s : statuses) {
                merged.add(s);
            }
            this.retryableStatuses = Set.copyOf(merged);
            return this;
        }

        /**
         * Escape hatch for callers that need to configure the underlying
         * {@link RestClient.Builder} (message converters, default headers,
         * additional interceptors, etc.).
         */
        public Spec customize(Consumer<RestClient.Builder> customizer) {
            this.customizer = customizer;
            return this;
        }

        public RestClient build() {
            Retry retry = retryRegistry.retry(instanceName);
            Function<URI, CircuitBreaker> cbLookup = buildCircuitBreakerLookup();

            RestClient.Builder builder = RestClient.builder();
            if (httpSettings != null) {
                ClientHttpRequestFactory requestFactory = ClientHttpRequestFactoryBuilder
                        .detect().build(httpSettings);
                builder.requestFactory(requestFactory);
            }
            builder.requestInterceptor(new ResilienceInterceptor(retry, cbLookup, retryableStatuses));
            if (customizer != null) {
                customizer.accept(builder);
            }
            return builder.build();
        }

        private Function<URI, CircuitBreaker> buildCircuitBreakerLookup() {
            if (perHostCircuitBreaker) {
                CircuitBreakerConfig baseConfig = circuitBreakerRegistry.getConfiguration(instanceName)
                        .orElse(circuitBreakerRegistry.getDefaultConfig());
                return uri -> circuitBreakerRegistry.circuitBreaker(
                        instanceName + "::" + hostOrEmpty(uri), baseConfig);
            }
            CircuitBreaker shared = circuitBreakerRegistry.circuitBreaker(instanceName);
            return _ -> shared;
        }

        private static String hostOrEmpty(URI uri) {
            String host = uri.getHost();
            return host == null ? "" : host;
        }
    }
}
