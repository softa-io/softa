package io.softa.starter.studio.release.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

import io.softa.framework.orm.enums.DatabaseType;
import io.softa.starter.studio.release.entity.DesignAppEnv;
import io.softa.starter.studio.release.enums.ConnectorType;
import io.softa.starter.studio.release.upgrade.RemoteApiClient;
import io.softa.starter.studio.release.ddl.DesignDdlTemplateResolver;

/**
 * {@link ConnectorFactory}: connectorType is data-driven (null ⇒ SOFTA). A SOFTA env
 * resolves to a {@link SoftaRuntimeConnector} on the builtin dialect for its (required)
 * {@code databaseType}; a JDBC env resolves to a {@link JdbcSchemaConnector} on the design-backed dialect.
 * Per-connectorType validation fails fast at build: SOFTA needs an {@code upgradeEndpoint}, JDBC a
 * {@code jdbcUrl}.
 */
class ConnectorFactoryTest {

    private final ConnectorFactory factory = new ConnectorFactory(
            mock(RemoteApiClient.class), mock(DesignDdlTemplateResolver.class),
            new JdbcDdlExecutor(), mock(JdbcSchemaReader.class));

    @Test
    void softaEnvResolvesToBuiltinDialectForItsDbType() {
        Connector connector = factory.forEnv(env(DatabaseType.MYSQL, ConnectorType.SOFTA));

        assertInstanceOf(SoftaRuntimeConnector.class, connector);
        assertEquals(DatabaseType.MYSQL, connector.dialect().getDatabaseType());
    }

    @Test
    void nullConnectorTypeDefaultsToSofta() {
        Connector connector = factory.forEnv(env(DatabaseType.POSTGRESQL, null));

        assertInstanceOf(SoftaRuntimeConnector.class, connector);
        assertEquals(DatabaseType.POSTGRESQL, connector.dialect().getDatabaseType());
    }

    @Test
    void missingDbTypeFailsFast() {
        assertThrows(RuntimeException.class, () -> factory.forEnv(env(null, ConnectorType.SOFTA)));
    }

    @Test
    void softaEnvWithoutEndpointFailsFast() {
        DesignAppEnv env = env(DatabaseType.MYSQL, ConnectorType.SOFTA);
        env.setUpgradeEndpoint(null);   // SOFTA addresses its runtime via the endpoint — mandatory

        assertThrows(RuntimeException.class, () -> factory.forEnv(env));
    }

    @Test
    void jdbcEnvResolvesToJdbcConnectorOnDesignDialect() {
        DesignAppEnv env = env(DatabaseType.MYSQL, ConnectorType.JDBC);
        env.setJdbcUrl("jdbc:mysql://db.example:3306/app");

        Connector connector = factory.forEnv(env);

        assertInstanceOf(JdbcSchemaConnector.class, connector);
        // The JDBC connector renders on the design-backed dialect for its flavor (P3.2); read/apply
        // behavior is covered by JdbcSchemaConnectorTest.
        assertEquals(DatabaseType.MYSQL, connector.dialect().getDatabaseType());
    }

    @Test
    void jdbcEnvWithoutUrlFailsFast() {
        // helper sets no jdbcUrl → a JDBC env cannot connect to its database
        assertThrows(RuntimeException.class, () -> factory.forEnv(env(DatabaseType.MYSQL, ConnectorType.JDBC)));
    }

    private static DesignAppEnv env(DatabaseType dbType, ConnectorType connectorType) {
        DesignAppEnv env = new DesignAppEnv();
        env.setId(1L);
        env.setDatabaseType(dbType);
        env.setConnectorType(connectorType);
        env.setUpgradeEndpoint("http://runtime.example");   // valid for SOFTA; JDBC ignores it
        return env;
    }
}
