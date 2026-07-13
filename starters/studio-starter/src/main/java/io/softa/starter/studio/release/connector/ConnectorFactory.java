package io.softa.starter.studio.release.connector;

import org.springframework.stereotype.Component;

import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.enums.DatabaseType;
import io.softa.starter.metadata.ddl.DdlDialectFactory;
import io.softa.starter.metadata.ddl.spi.DdlMetadataResolver;
import io.softa.starter.studio.release.entity.DesignAppEnv;
import io.softa.starter.studio.release.enums.ConnectorType;
import io.softa.starter.studio.release.upgrade.RemoteApiClient;
import io.softa.starter.studio.release.ddl.DesignDdlMetadataResolver;
import io.softa.starter.studio.release.ddl.DesignDdlTemplateResolver;

/**
 * Builds the {@link Connector} for a {@link DesignAppEnv}. Selection is data-driven on the
 * env's {@code connectorType} (null ⇒ {@link ConnectorType#SOFTA}).
 *
 * <p>The two connectors render DDL with different metadata sources:
 * <ul>
 *   <li><b>Softa</b> → builtin annotation/runtime defaults, identical to the boot scanner.</li>
 *   <li><b>JDBC</b> → a {@code design_*}-backed resolver adapted from
 *       {@link DesignDdlTemplateResolver}.</li>
 * </ul>
 *
 * <p>The resolver choice is explicit at this boundary; there is no shared Spring
 * {@code DdlMetadataResolver} or dialect registry to reason about.
 */
@Component
public class ConnectorFactory {

    /** design_*-backed resolver (forward FieldType→SQL via DesignFieldDbMapping) — for JDBC targets. */
    private final DdlMetadataResolver designDdlResolver;
    private final RemoteApiClient remoteApiClient;
    private final JdbcDdlExecutor jdbcDdlExecutor;
    private final JdbcSchemaReader jdbcSchemaReader;

    public ConnectorFactory(RemoteApiClient remoteApiClient,
                            DesignDdlTemplateResolver designResolver,
                            JdbcDdlExecutor jdbcDdlExecutor,
                            JdbcSchemaReader jdbcSchemaReader) {
        this.remoteApiClient = remoteApiClient;
        this.jdbcDdlExecutor = jdbcDdlExecutor;
        this.jdbcSchemaReader = jdbcSchemaReader;
        this.designDdlResolver = new DesignDdlMetadataResolver(designResolver);
    }

    public Connector forEnv(DesignAppEnv env) {
        Assert.notNull(env, "env must not be null");
        ConnectorType type = env.getConnectorType() == null ? ConnectorType.SOFTA : env.getConnectorType();
        return switch (type) {
            case SOFTA -> softaConnector(env);
            case JDBC -> jdbcConnector(env);
        };
    }

    private Connector softaConnector(DesignAppEnv env) {
        // databaseType is a required field on the env; the notNull guard is defensive.
        DatabaseType dbType = env.getDatabaseType();
        Assert.notNull(dbType,
                "Env {0} has no databaseType — cannot render DDL for its runtime.", env.getId());
        // Per-connectorType validation: a SOFTA env addresses its runtime through the signed upgrade API,
        // so the endpoint is mandatory here — it is no longer @Field(required), since a JDBC env has none.
        Assert.notBlank(env.getUpgradeEndpoint(),
                "SOFTA env {0} has no upgradeEndpoint — cannot address its runtime.", env.getId());
        return new SoftaRuntimeConnector(DdlDialectFactory.builtin(dbType), remoteApiClient, env);
    }

    private Connector jdbcConnector(DesignAppEnv env) {
        // A raw JDBC target: flavor + connection come from the env's embedded jdbc* fields
        // (no separate DesignConnector entity). The dialect renders on the design-backed mapping;
        // apply executes the rendered DDL against the external DB.
        DatabaseType dbType = env.getDatabaseType();
        Assert.notNull(dbType,
                "JDBC env {0} has no databaseType — cannot select its DDL dialect.", env.getId());
        Assert.notBlank(env.getJdbcUrl(),
                "JDBC env {0} has no jdbcUrl — cannot connect to its database.", env.getId());
        return new JdbcSchemaConnector(DdlDialectFactory.create(dbType, designDdlResolver),
                jdbcDdlExecutor, jdbcSchemaReader, env);
    }
}
