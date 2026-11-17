package io.softa.framework.orm.file;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where the signing secret comes from: {@code security.file-key.secret}, which deployments set as the
 * environment variable {@code SECURITY_FILE_KEY_SECRET}. And the app does not start without a usable one.
 */
class FileOpenKeyPropertiesTest {

    private static final String SECRET = "test-only-file-open-key-secret-0123456789";

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(FileOpenKeyProperties.class)
    static class Config {
    }

    /** A context whose environment variables are exactly these, whatever the shell running the test has set. */
    private static ApplicationContextRunner withEnvironment(Map<String, Object> variables) {
        return new ApplicationContextRunner()
                .withUserConfiguration(Config.class)
                .withInitializer(context -> context.getEnvironment().getPropertySources().replace(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        new SystemEnvironmentPropertySource(
                                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables)));
    }

    @Test
    void theEnvironmentVariableDeploymentsSetBindsToTheSecret() {
        withEnvironment(Map.of("SECURITY_FILE_KEY_SECRET", SECRET))
                .run(context -> assertThat(context.getBean(FileOpenKeyProperties.class).getSecret()).isEqualTo(SECRET));
    }

    @Test
    void withoutASecretTheAppDoesNotStart() {
        withEnvironment(Map.of())
                .run(context -> assertThat(context).getFailure()
                        .hasStackTraceContaining("security.file-key.secret must be set"));
    }

    @Test
    void aShortSecretStopsTheAppToo() {
        withEnvironment(Map.of("SECURITY_FILE_KEY_SECRET", "too-short"))
                .run(context -> assertThat(context).getFailure()
                        .hasStackTraceContaining("security.file-key.secret is too short"));
    }
}
