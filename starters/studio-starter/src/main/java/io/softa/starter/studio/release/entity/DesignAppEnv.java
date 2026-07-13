package io.softa.starter.studio.release.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.DatabaseType;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.starter.studio.release.enums.ConnectorType;
import io.softa.starter.studio.release.enums.DesignAppEnvStatus;
import io.softa.starter.studio.release.enums.DesignAppEnvType;

/**
 * DesignAppEnv Model — represents a deployment environment for a DesignApp.
 * <p>
 * Each env owns a full per-env design set; publishing (converge-to-HEAD) deploys that
 * design to the env's runtime. There is no version handle — the env's live design IS the deployed
 * content.
 * <p>
 * Concurrent deployments against the same env are guarded via {@code envStatus}.
 * A deployment may only start when {@code envStatus == STABLE}; it transitions the field to
 * {@code DEPLOYING} for the duration and releases it on completion (success or failure). The transition
 * is an atomic optimistic compare-and-set on {@code version} ({@code versionLock}) — see
 * {@code DesignAppEnvServiceImpl.acquireEnvLock}.
 * <p>
 * Authentication between Studio and the runtime targeted by this Env uses per-env
 * Ed25519 keypairs. Studio signs outgoing upgrade requests with {@code privateKey};
 * the runtime verifies against the corresponding {@code publicKey}. Rotation scope
 * is one env at a time — reissuing a keypair here does not disturb other runtimes.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(
        idStrategy = IdStrategy.DISTRIBUTED_LONG,
        versionLock = true
)
public class DesignAppEnv extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "App ID", required = true)
    private Long appId;

    @Field
    private DesignAppEnvStatus envStatus;

    @Field(label = "Env Name", required = true)
    private String name;

    @Field
    private Integer sequence;

    @Field
    private DesignAppEnvType envType;

    @Field(required = true, description = "Target runtime database flavor (moved here from DesignApp)")
    private DatabaseType databaseType;

    @Field(description = "Connector kind to the target — Softa runtime or raw JDBC")
    private ConnectorType connectorType;

    // JDBC connection — used only when connectorType = JDBC; there is no separate DesignConnector
    // entity. Required-ness is validated per-connectorType at connector build (ConnectorFactory),
    // not via @Field(required), since which fields are mandatory depends on connectorType.
    @Field(label = "JDBC URL", length = 256, description = "Raw JDBC connection URL when connectorType = JDBC")
    private String jdbcUrl;

    @Field(label = "JDBC Username", length = 128)
    private String jdbcUsername;

    @Field(label = "JDBC Password", length = 512, encrypted = true, copyable = false, unsearchable = true)
    private String jdbcPassword;

    @Field
    private Boolean protectedEnv;

    @Field
    private Boolean active;

    // Not @Field(required): mandatory only for connectorType = SOFTA (validated at connector build,
    // ConnectorFactory) — a JDBC env has no upgrade endpoint.
    @Field(label = "Upgrade API EndPoint", length = 128)
    private String upgradeEndpoint;

    @Field(length = 256)
    private String publicKey;

    // The signing private key is a secret: encrypted at rest, never returned by search, and not
    // carried by copyById (mirrors jdbcPassword). Encryption inflates the stored value, hence length 512.
    @Field(length = 512, encrypted = true, copyable = false, unsearchable = true)
    private String privateKey;

    @Field(label = "Auto Execute DDL")
    private Boolean autoExecuteDDL;

    @Field(length = 256)
    private String description;

    @Field(required = true, description = "Optimistic-lock version guarding the env-status mutex.")
    private Long version;
}
