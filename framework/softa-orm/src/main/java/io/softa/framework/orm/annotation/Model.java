package io.softa.framework.orm.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import io.softa.framework.orm.enums.IdStrategy;
import io.softa.framework.orm.enums.StorageType;

/**
 * Marks a Java class as a Softa metadata Model.
 * <p>
 * The annotated class <b>must</b> extend
 * {@link io.softa.framework.orm.entity.AbstractModel} (typically through
 * {@link io.softa.framework.orm.entity.AuditableModel}). The {@code modelName}
 * is derived from the class simple name (no override).
 *
 * <p><b>Requires {@code metadata-starter}</b> on the classpath to take effect.
 * softa-orm defines this annotation; {@code metadata-starter} contains the
 * scanner that reads it and reconciles {@code sys_model} rows / DDL. Without
 * {@code metadata-starter}, this annotation is parsed by the compiler but
 * never consumed at runtime.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Model {

    /**
     * Display label; empty = humanized class name (e.g. {@code DeptInfo -> "Dept Info"})
     * as the base default, overridden per-language via the i18n translation table
     * (keyed by {@code model.{modelName}}). Maps to {@code SysModel.label}.
     */
    String label() default "";

    /** DB table name; empty = derived from {@code snake_case(modelName)}. */
    String tableName() default "";

    /** Description shown to users in Studio UI; empty = no description. */
    String description() default "";

    /** Default list-display field names; empty = framework default. */
    String[] displayName() default {};

    /** Default search field names; empty = framework default. */
    String[] searchName() default {};

    /** Default order entries, e.g. {@code "createdTime:desc"}. */
    String[] defaultOrder() default {};

    /**
     * Soft-delete enabled. The flag field is always {@code deleted}
     * ({@code ModelConstant.SOFT_DELETED_FIELD}) — declare it on the entity as
     * {@code @Field private Boolean deleted;}. Map it onto a differently-named legacy
     * column with {@code @Field(columnName = "...")}; the logical field name is fixed,
     * exactly like {@code active} under {@link #activeControl()}.
     */
    boolean softDelete() default false;

    /** Active control enabled (adds 'active' column gating queries). */
    boolean activeControl() default false;

    /** Whether this is a timeline (effective-dated) model. */
    boolean timeline() default false;

    /** ID generation strategy. */
    IdStrategy idStrategy() default IdStrategy.DB_AUTO_ID;

    /** Storage backend. */
    StorageType storageType() default StorageType.RDBMS;

    /** Optimistic-lock (version column) enabled. */
    boolean versionLock() default false;

    /** Multi-tenant isolation enabled (adds 'tenantId' constraint to business tables). */
    boolean multiTenant() default false;

    /**
     * This model's rows are partitioned by country — one independent set per country,
     * so a value domain that differs between countries is stored as separate rows
     * rather than shared ones. Queries are then narrowed to the caller's countries
     * ({@code Context.grantedCountries}: the countries of the companies their roles
     * reach), automatically and for every read path (list / page / count / reference
     * lookup). A request that does not know its countries is not narrowed.
     *
     * <p>Boot-enforced: the model must carry a {@code MANY_TO_ONE} field onto
     * {@code CountryRegion} (see {@link io.softa.framework.orm.meta.ModelManager}).
     *
     * <p>Declare it <b>only</b> when the rows really are replicated per country.
     * A field merely recording <i>which country a record belongs to</i> — a legal
     * entity's country, an address's country — is not this: marking such a model
     * silently hides rows from every other country.
     */
    boolean multiCountry() default false;

    /**
     * Rows belong to one company. The framework does not narrow such a model by itself;
     * the permission layer ANDs the role's company grant onto every read of it, using the
     * anchor field this flag requires, so a role limited to some legal entities sees only
     * their rows. Roles without a company grant see every company's rows.
     *
     * <p>Boot-enforced: the model must carry a {@code MANY_TO_ONE} / {@code ONE_TO_ONE} onto
     * {@code LegalEntity}. A model with no company column of its own — a per-department statistic —
     * declares one as a {@code dynamic} cascaded field ({@code cascadedField =
     * "deptId.legalEntityId"}), which takes no column and is joined at query time. When several
     * references lead to a company, the one named {@code legalEntityId} is the owning one.
     *
     * <p>Named for the symmetry with {@link #multiCountry()} — the two are the same
     * mechanism on different axes, and one input drives both. Read it as "this model
     * spans companies", not as "a row is replicated per company": unlike the country
     * axis, where the same catalog genuinely exists once per country, these rows each
     * belong to exactly one company. Do not declare it on data shared across
     * companies — a tenant-wide code table, a model whose company field merely
     * records a preference — or every other company's rows become invisible.
     */
    boolean multiCompany() default false;


    /**
     * Whether rows of this model may be duplicated via {@code copyById} /
     * {@code copyByIds} / {@code getCopyableFields}. Set {@code false} on
     * runtime / log models (execution traces, send records, histories) that
     * have no duplicate scenario: the copy APIs then reject the model and the
     * UI hides the Duplicate action (exposed as {@code SysModel.copyable}).
     */
    boolean copyable() default true;

    /**
     * Whether writes to this model produce change logs — the record history behind the
     * form's Audit Log panel. Set {@code false} on derived / report models whose rows are
     * recomputed in bulk and carry no history worth keeping (the source data's own log is
     * the audit trail): create / update / delete then build no change log at all, which also
     * drops the transaction-bound buffer that holds every row's before / after until commit.
     * <p>The same log is what data-change flow triggers read, so a model with this off fires
     * no change-triggered flow (synchronous or asynchronous), and its rows do not appear in a
     * parent record's history. The stored-row read an update makes is unaffected — the diff,
     * computed fields and version check still need it. This flag decides per model whether a
     * log is built at all; {@code system.enable-change-log} still gates what leaves the process
     * (MQ persistence, asynchronous flows). Exposed as {@code SysModel.enableAuditLog}.
     */
    boolean enableAuditLog() default true;

    /**
     * Marks a read-only model over a table it does NOT own — the table belongs to
     * another model in this app (e.g. a report projecting {@code Employee}'s table)
     * or to an external process (e.g. a BI pipeline). Effects:
     * <ul>
     *   <li>the scanner never generates DDL for this model (no CREATE / ALTER /
     *       RENAME / DROP) — its {@code sys_*} metadata rows are still reconciled;</li>
     *   <li>the write APIs (create / update / delete / copy) reject the model;</li>
     *   <li>{@code @Index} declarations are rejected at boot — indexes belong to the
     *       table's owner;</li>
     *   <li>the physical drift audit still checks the declared columns exist, but never
     *       reports the table's other columns / indexes as undeclared (they belong to
     *       the owner), and a physically missing table logs an ERROR instead of failing
     *       the boot (the owner or the external process may simply not have created it
     *       yet).</li>
     * </ul>
     * Every non-projection RDBMS model claims exclusive DDL ownership of its resolved
     * table: two owners on one {@code tableName} fail at boot. RDBMS storage only.
     */
    boolean projection() default false;

    /** Override default data source; empty = primary data source. */
    String dataSource() default "";

    /** Business key field names (composite supported). */
    String[] businessKey() default {};

    /**
     * The single immediately-prior model name for a declared rename (empty = no rename).
     * Lets the metadata diff pair this model with it and emit {@code RENAME TABLE} (data preserved)
     * instead of drop+add. <b>Single-step</b> (no chain): multi-version lineage is not accumulated here —
     * studio derives it from snapshot history, the annotation lane handles a skipped version via manual
     * migration. Materialized into {@code sys_model.renamedFrom}. Replaces the retired {@code @RenamedFrom}
     * annotation.
     */
    String renamedFrom() default "";

    /** Partition field name for partitioned storage. */
    String partitionField() default "";
}
