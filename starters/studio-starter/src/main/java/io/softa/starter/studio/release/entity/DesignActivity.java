package io.softa.starter.studio.release.entity;

import java.io.Serial;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;
import tools.jackson.databind.JsonNode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.starter.studio.release.enums.DesignActivityKind;
import io.softa.starter.studio.release.enums.DesignActivityStatus;

/**
 * DesignActivity — the unified, immutable audit record of a studio operation against an env.
 * <p>
 * {@code kind} discriminates {@code PUBLISH} (design→runtime converge) / {@code IMPORT} (Softa runtime→design) /
 * {@code REVERSE} (raw JDBC physical schema→design) / {@code MERGE} (env→env). Studio operations are
 * synchronous, so {@code status} is just
 * {@code RUNNING}→{@code SUCCESS}/{@code FAILURE} (+ operator {@code CANCELED}).
 * <p>
 * This is a plain audit record, <b>not</b> per-env design metadata: it is keyed by a distributed
 * surrogate id and takes no part in business-key reconciliation, though it references its target env
 * by {@code envId}. {@code detail} holds the rendered DDL for a PUBLISH and is null for the other kinds;
 * {@code snapshotId} links the post-operation {@link DesignSnapshot} that {@code restore} replays.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(
        defaultOrder = "id DESC",
        idStrategy = IdStrategy.DISTRIBUTED_LONG,
        copyable = false
)
public class DesignActivity extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "App ID", required = true)
    private Long appId;

    @Field(label = "Env ID", required = true, description = "The environment this activity targets")
    private Long envId;

    @Field(required = true, description = "PUBLISH (design→runtime) / IMPORT (Softa runtime→design) / "
            + "REVERSE (JDBC physical→design) / MERGE (env→env)")
    private DesignActivityKind kind;

    @Field
    private DesignActivityStatus status;

    @Field(label = "Source Env ID", description = "MERGE only: the env whose design was merged from")
    private Long sourceEnvId;

    @Field(label = "Operator")
    private Long operatorId;

    @Field
    private LocalDateTime startedTime;

    @Field
    private LocalDateTime finishedTime;

    @Field(description = "The per-row change set applied (all kinds)")
    private JsonNode changeSet;

    @Field(description = "Rendered DDL — PUBLISH only; null for IMPORT / REVERSE / MERGE (change set + snapshot are the audit)")
    private JsonNode detail;

    @Field(description = "The post-operation DesignSnapshot this activity produced (restore source)")
    private Long snapshotId;

    @Field(length = 20000)
    private String errorMessage;
}
