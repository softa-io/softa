package io.softa.starter.studio.release.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import io.softa.framework.web.resilience.ResilientRestClientBuilder;
import io.softa.starter.studio.release.constant.ReleaseConstant;
import io.softa.starter.studio.release.signing.DesignAppEnvSigningInterceptor;

/**
 * Builds the {@code studioRemoteRestClient} bean used to talk to remote Softa
 * runtime environments.
 * <p>
 * Retry / circuit-breaker policies live in {@code application.yml} under
 * {@code resilience4j.retry.instances.studio-remote} and
 * {@code resilience4j.circuitbreaker.instances.studio-remote}; this config only
 * owns the HTTP-level settings (timeouts) that are local to the studio client.
 * <p>
 * Interceptor order: the resilience interceptor is added first by the builder,
 * so it sits outermost — its Retry loop wraps the signing interceptor we add via
 * {@link ResilientRestClientBuilder.Spec#customize}. That ordering matters: each
 * retry re-enters signing, so a retried request gets a fresh timestamp + nonce
 * and never trips the clock-skew window or the remote side's replay guard.
 */
@Configuration
public class StudioRemoteClientConfig {

    @Bean(name = "studioRemoteRestClient")
    @ConditionalOnMissingBean(name = "studioRemoteRestClient")
    public RestClient studioRemoteRestClient(ResilientRestClientBuilder builder,
                                             DesignAppEnvSigningInterceptor signingInterceptor) {
        HttpClientSettings settings = HttpClientSettings.defaults()
                .withConnectTimeout(ReleaseConstant.CONNECT_TIMEOUT)
                .withReadTimeout(ReleaseConstant.READ_TIMEOUT);
        return builder.name("studio-remote")
                .httpSettings(settings)
                .perHostCircuitBreaker()
                .customize(b -> b.requestInterceptor(signingInterceptor))
                .build();
    }
}
