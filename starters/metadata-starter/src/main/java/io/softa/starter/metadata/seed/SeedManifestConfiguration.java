package io.softa.starter.metadata.seed;

import org.springframework.boot.autoconfigure.condition.ConditionalOnResource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes the application's {@link SeedManifest} when it ships one, read and checked at startup: a
 * manifest that cannot be used stops the application from starting instead of failing the first tenant
 * provisioned. An application without a manifest gets no bean and is unaffected.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnResource(resources = "classpath:" + SeedManifestReader.DEFAULT_LOCATION)
public class SeedManifestConfiguration {

    @Bean
    public SeedManifest seedManifest() {
        return SeedManifestReader.readClasspath();
    }
}
