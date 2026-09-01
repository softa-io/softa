package io.softa.starter.studio.meta.entity;

import java.io.Serial;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.framework.orm.enums.IndexType;

/**
 * DesignModelIndex Model
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(
        idStrategy = IdStrategy.DISTRIBUTED_LONG,
        // hard delete (no softDelete) — lets the per-env UNIQUE(env_id, …) index work. See DesignModel.
        copyable = false,   // copy disabled (would clone the per-env business key) — see DesignModel.
        // envId scopes the businessKey (see DesignModel).
        businessKey = {"envId", "modelName", "indexName"}
)
public class DesignModelIndex extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "APP ID")
    private Long appId;

    @Field(label = "Model ID", required = true)
    private Long modelId;

    // Per-env design: envId scopes the row (NOT NULL, V19). Identity = per-env business key
    // (env_id + modelName + indexName); no logicalId.
    @Field(label = "Env ID")
    private Long envId;

    @Field(required = true)
    private String modelName;

    // Globally unique across all models; capped at 60 chars (mirror of SysModelIndex).
    @Field(length = 60)
    private String indexName;

    @Field
    private List<String> indexFields;

    @Field(label = "Is Unique Index")
    private Boolean uniqueIndex;

    // What the index is for; the dialect turns it into an access method (mirror of
    // SysModelIndex.indexType). Nullable = BTREE.
    @Field
    private IndexType indexType;

    // End-user message for a violation of this unique constraint (mirror of SysModelIndex.message).
    @Field(length = 256)
    private String message;
}
