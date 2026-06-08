package io.softa.framework.orm.annotation;

import java.lang.annotation.*;

/**
 * Declares a database index on the annotated {@link Model} class.
 * <p>
 * Repeatable: stack multiple {@code @Index(...)} declarations on the same
 * class to declare multiple indexes.
 *
 * <pre>{@code
 * @Index(fields = {"status"})
 * @Index(indexName = "uk_customer_email", fields = {"email"}, unique = true)
 * @Index(fields = {"createdTime", "tenantId"})
 * @Model(...)
 * public class Customer { ... }
 * }</pre>
 *
 * <p>{@link #fields()} references {@code @Field}-annotated Java field names
 * (camelCase). The parser resolves these to DB column names via each field's
 * {@link Field#columnName()} (or {@code snake_case(fieldName)} default).
 *
 * <p>The {@code MetadataAnnotationScanner} writes resulting {@code sys_model_index}
 * rows with {@code ownership = PLATFORM_MAINTAINED}; the DDL orchestrator emits
 * {@code CREATE INDEX} / {@code ADD INDEX} (auto-executed) or {@code DROP INDEX}
 * (warn-only, never auto-executed).
 *
 * <p>Note: this annotation does NOT auto-derive an index from
 * {@link Model#businessKey()}. Multi-tenant models typically want
 * {@code UNIQUE (tenant_id, businessKey...)} which has tenant-aware semantics
 * not yet expressed by this annotation; declare such indexes explicitly via
 * a separate {@code @Index(fields = {"tenantId", "code"}, unique = true)}.
 *
 * <p><b>Requires {@code metadata-starter}</b> on the classpath to take effect
 * (see {@link Model}).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(Indexes.class)
public @interface Index {

    /**
     * SQL index identifier; empty = parser auto-derives as
     * {@code uk_<table>_<col1>_<col2>...} (unique) or
     * {@code idx_<table>_<col1>_<col2>...} (non-unique), truncated to the
     * dialect's identifier limit (MySQL 64, PostgreSQL 63). Maps to
     * {@code SysModelIndex.indexName} / {@code sys_model_index.index_name}.
     */
    String indexName() default "";

    /**
     * Human-readable label for the index (Studio UI / metadata display).
     * Empty = parser fills it with the final {@link #indexName()} value
     * (explicit or auto-derived). Maps to {@code SysModelIndex.label} /
     * {@code sys_model_index.label}.
     */
    String label() default "";

    /**
     * Field names (in the model's {@code @Field} domain — camelCase Java
     * field names, NOT column names). Required, non-empty.
     */
    String[] fields();

    /**
     * Unique constraint. Default false (regular non-unique index).
     */
    boolean unique() default false;
}
