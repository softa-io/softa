package io.softa.starter.metadata.entity;

import java.io.Serial;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IndexType;

/**
 * SysModelIndex — metadata catalog row describing a model's database index.
 *
 * <p>Self-described via {@code @Model} + per-field {@code @Field} so the
 * scanner manages its schema like any other SYSTEM_MODEL.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(
        label = "System Model Index",
        businessKey = {"modelName", "indexName"},
        description = "Metadata catalog of indexes"
)
public class SysModelIndex extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field
    private String appCode;

    @Field(required = true)
    private String modelName;

    // Surrogate FK to the owning model. relatedField defaults to id (BIGINT). Nullable and
    // EXCLUDED from the scanner diff: resolved post-scan from modelName — see SysReferenceSql.
    @Field(fieldType = FieldType.MANY_TO_ONE, relatedModel = SysModel.class)
    private Long modelId;

    // Globally unique across all models (enforced at metadata load). Capped at 60 chars
    // (safe under both MySQL 64 and PostgreSQL 63); the parser rejects an over-length name.
    @Field(required = true, length = 60)
    private String indexName;

    @Field(required = true)
    private List<String> indexFields;

    @Field(label = "Is Unique Index")
    private Boolean uniqueIndex;

    // What the index is for; the dialect turns it into an access method. Nullable = BTREE, so
    // every row written before this column existed keeps rendering exactly as it did.
    @Field
    private IndexType indexType;

    // End-user message for a violation of this unique constraint (its own i18n key).
    // Nullable: null = no custom message (generic composed fallback is used).
    @Field(length = 256)
    private String message;

}
