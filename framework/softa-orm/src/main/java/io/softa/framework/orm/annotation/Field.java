package io.softa.framework.orm.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.MaskingType;
import io.softa.framework.orm.enums.OnDelete;
import io.softa.framework.orm.enums.WidgetType;

/**
 * Marks a Java field on a {@link Model}-annotated class as a Softa metadata Field.
 * <p>
 * The {@code fieldName} is derived from the Java field name (no override).
 * Only declared fields are processed—inherited audit fields from
 * {@code AuditableModel} are skipped via {@code Class.getDeclaredFields()}.
 *
 * <p><b>Requires {@code metadata-starter}</b> on the classpath to take effect
 * (see {@link Model}).
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Field {

    /**
     * Display label; empty = humanized field name (e.g. {@code deptId -> "Dept Id"})
     * as the base default, overridden per-language via the i18n translation table
     * (keyed by {@code field.{modelName}.{fieldName}}). Maps to {@code SysField.label}.
     */
    String label() default "";

    /** Description shown to users in Studio UI; empty = no description. */
    String description() default "";

    /**
     * Field type. Empty array = inferred from Java type via
     * single element = explicit override.
     */
    FieldType[] fieldType() default {};

    /**
     * The single immediately-prior field name for a declared rename (empty = no rename).
     * Lets the metadata diff pair this field with it and emit {@code CHANGE COLUMN} (data preserved)
     * instead of drop+add. <b>Single-step</b> (no chain): multi-version lineage is not accumulated here —
     * studio derives it from snapshot history, the annotation lane handles a skipped version via manual
     * migration. Materialized into {@code sys_field.renamedFrom}. Replaces the retired {@code @RenamedFrom}
     * annotation.
     */
    String renamedFrom() default "";

    /** DB column name; empty = derived from {@code snake_case(fieldName)}. */
    String columnName() default "";

    /** Length (STRING / DECIMAL precision); 0 = scanner picks type-specific default. */
    int length() default 0;

    /** Scale (DOUBLE / BIG_DECIMAL); 0 = scanner picks type-specific default. */
    int scale() default 0;

    /** Required (NOT NULL). Default reflects Java primitive (true) vs wrapper (false). */
    boolean required() default false;

    /** Readonly (not editable in default views). */
    boolean readonly() default false;

    /** I18n-translatable column. */
    boolean translatable() default false;

    /**
     * Carried over by {@code copyById} / {@code getCopyableFields}. Set
     * {@code false} on fields whose value must not survive a duplicate:
     * business keys / unique columns, credentials, runtime state
     * (counters, last/next-execution times). Model-level
     * {@link Model#copyable()} switches the whole model off instead.
     */
    boolean copyable() default true;

    /** Excluded from default search. */
    boolean unsearchable() default false;

    /** Computed (formula) field; requires {@link #expression()}. */
    boolean computed() default false;

    /** Compute expression (AviatorScript); required when {@link #computed()}=true. */
    String expression() default "";

    /** Dynamic field (not stored). */
    boolean dynamic() default false;

    /** Encrypted at rest. */
    boolean encrypted() default false;

    /**
     * Auto-fill from a sequence on INSERT when the incoming value is null/blank;
     * caller-provided values are kept as-is. Pairs with a {@code sys_sequence} row
     * whose code is {@code "<ModelName>.<fieldName>"} — the rendering template,
     * reset cadence and allocation mode live on that row, not here. STRING fields
     * only (the rendered value is a string, e.g. {@code "EMP-00042"}); the scanner
     * rejects other field types at parse time. See {@code SequenceProcessor}.
     */
    boolean autoSequence() default false;

    /**
     * Masking strategy when rendering. Empty array = no masking;
     * single element = explicit masking type.
     */
    MaskingType[] maskingType() default {};

    /** Default value expression. */
    String defaultValue() default "";

    /**
     * Related model <b>class</b> (relational types) — preferred, compile-checked
     * form, e.g. {@code relatedModel = Customer.class}. {@code Void.class}
     * (default) = not set → falls back to {@link #relatedModelName()}, then to
     * inference from the Java field's POJO type. The model name is the class's
     * simple name. <b>Required</b> (via this or {@link #relatedModelName()}) when
     * the Java type is {@code Long} (storing FK id only).
     */
    Class<?> relatedModel() default Void.class;

    /**
     * Related model <b>name</b> (String form) — fallback for cross-module /
     * dynamic models not on the compile classpath. Ignored when
     * {@link #relatedModel()} is set to a non-{@code Void} class.
     */
    String relatedModelName() default "";

    /**
     * Field on the related model this relation joins to.
     *
     * <p>For {@code MANY_TO_ONE} / {@code ONE_TO_ONE} (to-one): always the related model's
     * {@code "id"} — leave empty (a TO_ONE relation joins on the surrogate id only; a non-id
     * value is rejected at boot). To store a portable business code in the FK, make the related model
     * <b>code-as-id</b> ({@code EXTERNAL_ID}, id = the code, e.g. {@code Currency}/{@code CountryRegion}):
     * the FK then stores the code while the join stays id-native, and the FK column mirrors the
     * referenced id type ({@code VARCHAR} for a String code-as-id, {@code BIGINT} for a Long surrogate).
     *
     * <p>For {@code ONE_TO_MANY}: names the column on the child that references this
     * model (required, must not be {@code "id"}).
     */
    String relatedField() default "";

    /**
     * FK delete strategy for a {@code MANY_TO_ONE} / {@code ONE_TO_ONE} relation: what happens to
     * the referencing rows when the referenced ("One") row is deleted. Empty array = unset = KEEP
     * (framework does nothing, FK left as-is — the default). A single value {@code RESTRICT} /
     * {@code CASCADE} / {@code SET_NULL} is an explicit policy. Meaningful only on TO_ONE relations
     * (rejected on other field types at boot).
     */
    OnDelete[] onDelete() default {};

    /**
     * Many-to-many join model <b>class</b> — preferred, compile-checked form,
     * e.g. {@code joinModel = EmpProjectRel.class}. {@code Void.class} (default)
     * = not set → falls back to {@link #joinModelName()}.
     */
    Class<?> joinModel() default Void.class;

    /** Many-to-many join model <b>name</b> (String form) — fallback for cross-module / dynamic models. */
    String joinModelName() default "";

    /** Many-to-many join model's left field. */
    String joinLeft() default "";

    /** Many-to-many join model's right field. */
    String joinRight() default "";

    /** Cascaded field path, e.g. {@code "owner.name"}. */
    String cascadedField() default "";

    /** Filter expression for relational queries. */
    String filters() default "";

    /**
     * UI widget type override. Empty array = no override; the framework picks
     * the default presentation for the field's {@code fieldType} at runtime
     * (no compile-time auto-inference). Single element = explicit override,
     * e.g. {@code widgetType = WidgetType.TEXT}. AI / human writes the
     * single value directly — Java auto-wraps it into a single-element array.
     */
    WidgetType[] widgetType() default {};
}
