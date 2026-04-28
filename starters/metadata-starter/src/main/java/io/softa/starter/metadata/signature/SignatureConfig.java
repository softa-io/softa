package io.softa.starter.metadata.signature;

import java.security.PublicKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.softa.framework.web.signature.Ed25519Keys;

import static io.softa.starter.metadata.constant.MetadataConstant.SIGNED_URL_PATTERN;

/**
 * {@link SignatureVerificationFilter} is registered iff
 * {@code system.runtime-public-key} is set, so services that never
 * receive signed requests don't carry a stray filter.
 * <p>
 * The filter is scoped to SIGNED_URL_PATTERN via
 * {@link FilterRegistrationBean} and unconditionally verifies every request
 * routed through it. The path prefix is the contract: anything mapped under
 * it is signed; anything not under it is not. There is no per-handler
 * override.
 */
@Configuration
public class SignatureConfig {

    @Value("${system.runtime-public-key:}")
    private String runtimePublicKey;

    @Bean
    @ConditionalOnExpression("!'${system.runtime-public-key:}'.isBlank()")
    public FilterRegistrationBean<SignatureVerificationFilter> signatureVerificationFilter() {
        PublicKey decoded = Ed25519Keys.decodePublicKey(runtimePublicKey);
        SignatureVerificationFilter filter = new SignatureVerificationFilter(decoded);
        FilterRegistrationBean<SignatureVerificationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns(SIGNED_URL_PATTERN);
        return registration;
    }
}
