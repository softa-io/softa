package io.softa.starter.metadata.signature;

import java.security.PublicKey;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerMapping;

import io.softa.framework.web.signature.Ed25519Keys;

/**
 * {@link SignatureVerificationFilter} is registered iff
 * {@code system.runtime-public-key} is set, so services that never
 * receive signed requests don't carry a stray filter.
 * <p>
 * The filter is scoped to {@code /metadata/*} via {@link FilterRegistrationBean}
 * so requests to other paths bypass it entirely — handler resolution and body
 * buffering are only ever paid on metadata API calls.
 */
@Configuration
public class SignatureConfig {

    @Value("${system.runtime-public-key:}")
    private String runtimePublicKey;

    @Bean
    @ConditionalOnExpression("!'${system.runtime-public-key:}'.isBlank()")
    public FilterRegistrationBean<SignatureVerificationFilter> signatureVerificationFilter(
            ObjectProvider<List<HandlerMapping>> handlerMappings) {
        PublicKey decoded = Ed25519Keys.decodePublicKey(runtimePublicKey);
        SignatureVerificationFilter filter = new SignatureVerificationFilter(
                decoded, handlerMappings.getIfAvailable(List::of));
        FilterRegistrationBean<SignatureVerificationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/metadata/*");
        return registration;
    }
}
