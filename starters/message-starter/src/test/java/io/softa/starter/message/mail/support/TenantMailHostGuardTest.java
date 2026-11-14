package io.softa.starter.message.mail.support;

import java.net.InetAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.exception.BusinessException;
import io.softa.starter.message.config.MessageProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A tenant's mail server stays on the internet ({@link TenantMailHostGuard}). Literal addresses only,
 * so nothing here depends on DNS.
 */
class TenantMailHostGuardTest {

    private MessageProperties properties;
    private TenantMailHostGuard guard;

    @BeforeEach
    void setUp() {
        properties = new MessageProperties();
        guard = new TenantMailHostGuard(properties);
        SystemConfig config = new SystemConfig();
        config.setEnableMultiTenancy(true);
        SystemConfig.env = config;
    }

    @AfterEach
    void tearDown() {
        SystemConfig.env = null;
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "10.1.2.3", "172.16.0.9", "192.168.1.1", "169.254.169.254",
            "100.64.0.1", "0.0.0.0", "224.0.0.1", "255.255.255.255", "::1", "::", "fe80::1", "fd00::1",
            "::ffff:10.0.0.1"})
    void addressesInsideTheDeploymentAreInternal(String literal) throws Exception {
        assertThat(TenantMailHostGuard.isInternal(InetAddress.getByName(literal))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"8.8.8.8", "203.0.113.10", "2001:4860:4860::8888"})
    void internetAddressesAreNot(String literal) throws Exception {
        assertThat(TenantMailHostGuard.isInternal(InetAddress.getByName(literal))).isFalse();
    }

    @Test
    void anInternalHostIsRefusedOnSaveAndBeforeConnecting() {
        assertThat(guard.refusalOnSave("10.0.0.5")).contains("inside the deployment");
        assertThrows(BusinessException.class, () -> guard.requirePublicBeforeConnecting("127.0.0.1"));
    }

    @Test
    void aPublicHostPasses() {
        assertThat(guard.refusalOnSave("8.8.8.8")).isNull();
        assertDoesNotThrow(() -> guard.requirePublicBeforeConnecting("8.8.8.8"));
    }

    @Test
    void theSwitchTurnsTheCheckOff() {
        properties.getMail().setBlockPrivateTenantHosts(false);

        assertThat(guard.refusalOnSave("127.0.0.1")).isNull();
        assertDoesNotThrow(() -> guard.requirePublicBeforeConnecting("127.0.0.1"));
    }

    @Test
    void singleTenantDeploymentsAreNotChecked() {
        SystemConfig.env = null;

        assertThat(guard.active()).isFalse();
        assertDoesNotThrow(() -> guard.requirePublicBeforeConnecting("127.0.0.1"));
    }
}
