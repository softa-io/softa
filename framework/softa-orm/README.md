# Softa ORM

## Metadata Annotations

> **Requires `metadata-starter`** as a dependency of your app for these
> annotations to take effect. `softa-orm` defines the annotations;
> `metadata-starter` contains the scanner and checker that read them and
> reconcile with `sys_*`. Without `metadata-starter` the annotations exist
> on your classes but no scanner consumes them — `sys_*` rows are never
> written and no DDL is generated.

Softa describes models, fields, option sets, option items, and indexes
through Java annotations on the entity classes. A boot-time scanner reads
these annotations, reconciles them with the `sys_*` catalog tables managed
by `metadata-starter`, and (for packages in `scanner-scope`) applies the matching DDL.

**Five annotations**, all in `io.softa.framework.orm.annotation`:

| Annotation | Target | `sys_*` table written | Purpose |
|---|---|---|---|
| `@Model` | class | `sys_model` | Describes an entity (table, business key, multi-tenancy, soft delete, etc.) |
| `@Field` | field | `sys_field` | Describes a column (label, type, length, required, relations, etc.) |
| `@OptionSet` | enum class | `sys_option_set` | Marks an enum as a managed option set |
| `@OptionItem` | enum constant | `sys_option_item` | Per-constant display attributes |
| `@Index` | class (`@Repeatable`) | `sys_model_index` | Declares a database index |

```java
@Data
@EqualsAndHashCode(callSuper = true)
@Model(
    label = "Customer",
    businessKey = {"code"},
    description = "Customer master"
)
@Index(indexName = "uk_customer_code", fields = {"code"}, unique = true)
@Index(fields = {"status", "createdTime"})
public class Customer extends AuditableModel {

    @Field(label = "ID")
    private Long id;

    @Field(label = "Customer Code", required = true, length = 32)
    private String code;

    @Field(label = "Customer Tier")
    private CustomerTier tier;   // enum → FieldType.OPTION (inferred)
}

@OptionSet(label = "Customer Tier")
public enum CustomerTier {
    @OptionItem(label = "VIP Gold") GOLD("g"),   // explicit: "VIP Gold" ≠ humanize("GOLD")
    SILVER("s");                                 // bare: label defaults to humanize("SILVER") = "Silver"

    @JsonValue private final String code;       // itemCode = @JsonValue
    CustomerTier(String code) { this.code = code; }
}
```

### Inference rules (no annotation needed)

| Concept | Derived from | Override |
|---|---|---|
| `modelName` | class simple name | — (no override) |
| `fieldName` | Java field name | — (no override) |
| `optionSetCode` | enum class simple name | — (no override) |
| `itemCode` | `@JsonValue` field value (fallback `enum.name()`) | — (no override) |
| `tableName` | `snake_case(modelName)` | `@Model.tableName` |
| `columnName` | `snake_case(fieldName)` | `@Field.columnName` |
| `fieldType` | Java type via `TypeInference` (e.g. `String`→`STRING`, enum→`OPTION`, `List<enum>`→`MULTI_OPTION`, `@Model` POJO→`MANY_TO_ONE`) | `@Field.fieldType = FieldType.X` (single value, no braces); **`OPTION` / `MULTI_OPTION` cannot be written explicitly** |
| index `indexName` | `idx_<table>_<col>...` / `uk_<table>_<col>...` for unique; **globally unique** (boot-enforced), ≤60 chars | `@Index.indexName` |

### `@Model` ↔ `SysModel`

| `@Model` attribute | Type | Default | `SysModel` column | Notes |
|---|---|---|---|---|
| (class simple name) | — | — | `modelName` | inferred, no override |
| `label` | String | `""` | `label` | empty → humanized class name (`DeptInfo`→"Dept Info"); i18n translations override by id |
| `tableName` | String | `""` | `tableName` | empty → `snake_case(modelName)` |
| `description` | String | `""` | `description` | **≤512 chars**, parse-time enforced (catalog column width); concise user-facing summary — design notes go in Javadoc |
| `displayName` | String[] | `{}` | `displayName` | list-display defaults |
| `searchName` | String[] | `{}` | `searchName` | search-field defaults |
| `defaultOrder` | String[] | `{}` | `defaultOrder` | e.g. `"createdTime:desc"` |
| `softDelete` | boolean | `false` | `softDelete` | |
| `softDeleteField` | String | `"deleted"` | `softDeleteField` | effective only when `softDelete = true` |
| `activeControl` | boolean | `false` | `activeControl` | adds `active` gate column |
| `timeline` | boolean | `false` | `timeline` | effective-dated rows (see Timeline Model) |
| `idStrategy` | `IdStrategy` | `DB_AUTO_ID` | `idStrategy` | |
| `storageType` | `StorageType` | `RDBMS` | `storageType` | |
| `versionLock` | boolean | `false` | `versionLock` | optimistic-lock column |
| `multiTenant` | boolean | `false` | `multiTenant` | requires a `tenantId` field on the class |
| `copyable` | boolean | `true` | `copyable` | `false` ⇒ copy APIs reject the model; UI hides Duplicate |
| `dataSource` | String | `""` | `dataSource` | empty → primary datasource |
| `businessKey` | String[] | `{}` | `businessKey` | composite supported |
| `partitionField` | String | `""` | `partitionField` | |
| (scanner sets) | — | — | `appCode` | stamped server-side from `system.app-code` |
| (DB auto) | — | — | `id` | primary key |

Audit fields (`createdTime` / `createdBy` / `createdId` / `updatedTime` /
`updatedBy` / `updatedId`) come from `AuditableModel` and are **not** declared
via `@Field` — they are auto-injected by `DdlGenerator` when the class
extends `AuditableModel`.

### `@Field` ↔ `SysField`

| `@Field` attribute | Type | Default | `SysField` column | Notes |
|---|---|---|---|---|
| (Java field name) | — | — | `fieldName` | inferred, no override |
| (Java type) | — | — | `fieldType` | inferred via `TypeInference` |
| `label` | String | `""` | `label` | empty → humanized field name (`deptId`→"Dept Id"); i18n translations override by id |
| `description` | String | `""` | `description` | **≤512 chars**, parse-time enforced (catalog column width); concise user-facing summary — design notes go in Javadoc |
| `fieldType` | `FieldType[]` | `{}` | `fieldType` | single value, no braces (e.g. `fieldType = FieldType.MULTI_FILE`); `OPTION`/`MULTI_OPTION` **cannot** be written explicitly |
| `columnName` | String | `""` | `columnName` | empty → `snake_case(fieldName)` |
| `length` | int | `0` | `length` | `0` → type default: STRING/OPTION 64, MULTI_STRING/ORDERS 256, DOUBLE 24 (measurements), BIG_DECIMAL 32 (money); declare explicitly for anything else. MySQL renders `length > 16383` as TEXT |
| `scale` | int | `0` | `scale` | `0` → type default: DOUBLE 2, BIG_DECIMAL 8 (DECIMAL scale) |
| `required` | boolean | `false` | `required` | NOT NULL constraint |
| `readonly` | boolean | `false` | `readonly` | UI hint |
| `translatable` | boolean | `false` | `translatable` | i18n-aware column |
| `copyable` | boolean | `true` | `copyable` | `false` ⇒ value not carried over by `copyById` (business keys, credentials, runtime state) |

Copy field-selection contract (applies regardless of the flag): `ONE_TO_ONE` FKs are **always
excluded** — copying one would make two rows share an exclusively-owned related row, corrupting the
1:1 (or hard-failing on its unique index); dynamic fields (`ONE_TO_MANY` / `MANY_TO_MANY` / computed /
cascaded) are excluded because they are not stored columns; `MANY_TO_ONE` **stays copyable** — a shared
reference is exactly its semantics. Historical trap: the `nonCopyable` → `copyable` rename was done as a
migration (V6), NOT via `renamedFrom`, because the rename inverts the value's meaning — a
value-preserving rename would have carried wrong values.
| `unsearchable` | boolean | `false` | `unsearchable` | excluded from default search |
| `computed` | boolean | `false` | `computed` | requires `expression` |
| `expression` | String | `""` | `expression` | AviatorScript |
| `dynamic` | boolean | `false` | `dynamic` | not physically stored |
| `encrypted` | boolean | `false` | `encrypted` | at-rest encryption |
| `autoSequence` | boolean | `false` | `auto_sequence` | auto-fill from a sequence on INSERT when blank; STRING only (not `dynamic`/`computed`/id, RDBMS only); pairs with a `sys_sequence` row `"<Model>.<field>"` (missing row = insert fails, fail-closed). `+ readonly` = strict system numbering (caller values rejected); without = caller values trusted (imports). Never carried on copy |
| `maskingType` | `MaskingType[]` | `{}` | `maskingType` | single element |
| `defaultValue` | String | `""` | `defaultValue` | |
| `relatedModel` | `Class<?>` | `Void.class` | `relatedModel` | Class ref (compile-checked), e.g. `Foo.class`; `Void.class` → inferred from POJO type; **required** for `Long` FK. Use `relatedModelName` (String) for cross-module/dynamic models |
| `relatedModelName` | String | `""` | `relatedModel` | String fallback to `relatedModel` (cross-module/dynamic) |
| `relatedField` | String | `""` | `relatedField` | TO_ONE: always `id` — leave empty (a non-id value is rejected at boot; to store a business code make the related model code-as-id). ONE_TO_MANY: names the child FK column |
| `onDelete` | `OnDelete[]` | `{}` | `on_delete` | TO_ONE FK delete strategy: `RESTRICT` / `CASCADE` / `SET_NULL`; `{}`/unset = KEEP (default — do nothing). App-level (no DB FK). See "Delete strategy" below |
| `joinModel` | `Class<?>` | `Void.class` | `joinModel` | M2M join model class; `joinModelName` (String) fallback |
| `joinLeft` | String | `""` | `joinLeft` | |
| `joinRight` | String | `""` | `joinRight` | |
| `cascadedField` | String | `""` | `cascadedField` | dotted path, e.g. `"owner.name"` |
| `filters` | String | `""` | `filters` | filter expression for relations |
| `widgetType` | `WidgetType[]` | `{}` | `widgetType` | single-element override |
| (scanner sets) | — | — | `modelName` | from enclosing `@Model` class |
| (scanner sets) | — | — | `optionSetCode` | derived from enum type when fieldType is `OPTION`/`MULTI_OPTION` |
| (scanner sets) | — | — | `appCode` / `id` | |
| (FK fixup post-init) | — | — | `modelId` | |
| (not exposed via `@Field`) | — | — | `hidden` | UI-only flag set via Studio |

#### Delete strategy (`onDelete`)

On a `MANY_TO_ONE` / `ONE_TO_ONE` FK, `onDelete` declares what happens to the **referencing** rows when
the referenced ("One") row is deleted. Enforced application-level in `ModelServiceImpl.deleteByIds` — no
physical DB `FOREIGN KEY ... ON DELETE` is ever emitted. Why app-level and never a real DB FK: soft
delete is an `UPDATE`, invisible to a DB `ON DELETE` (the FK would simply never fire); a DB cascade
bypasses permissions, change logs, audit stamping, soft-delete conversion and tenant scoping; a DB FK
cannot express "count only `deleted=false` referrers", "block regardless of tenant", or "null only on
hard delete"; and physical FKs clash with the never-auto-DROP DDL governance. Strategies:

- `RESTRICT` — block the delete if any live (`deleted=false`) referrer exists.
- `CASCADE` — delete the referrers in the same transaction (each follows its own soft/hard delete).
  **Rejected at boot** if a soft-delete One would cascade to a hard-delete Many (a recoverable parent
  must not irreversibly delete children — make the Many soft-delete too, or use RESTRICT/SET_NULL).
- `SET_NULL` — null the referrer FK; **only on a hard delete** of the One (no-op on soft delete, so a
  restore still resolves the link). Requires a nullable FK (`required = false`).
- unset (`{}` / `on_delete` NULL) = **KEEP** (default) — the framework does nothing.

**CASCADE soft/hard-delete matrix** — the cascade on each Many follows the *Many's* own delete mode
(not the One's); the one unsafe combination is rejected at boot:

| One (referenced / parent) | Many (referrer / child) | CASCADE result |
|---|---|---|
| soft-delete | soft-delete | Many **soft-deleted** (both recoverable) |
| soft-delete | hard-delete | **rejected at boot** — a recoverable parent must not irreversibly delete children |
| hard-delete | soft-delete | Many **soft-deleted** |
| hard-delete | hard-delete | Many **hard-deleted** |

A `CASCADE` from a **shared (non-multi-tenant) parent to a multi-tenant child** is likewise rejected at
boot — one delete would cascade across all tenants (use RESTRICT).

**Runtime safety** — a `CASCADE` / `SET_NULL` affecting more than `MAX_BATCH_SIZE` referrers *per cascade
level* is rejected: `referrerIds` fetches at most `MAX_BATCH_SIZE + 1` ids in one `LIMIT`-ed query, so an
over-limit delete fails fast **without loading the full set** (bounded memory, no extra `count`). Large
deletes are chunked to `DEFAULT_BATCH_SIZE` to bound the SELECT/DELETE statement + IN-clause size (same
transaction — chunking bounds statement size, not lock duration).

For a OneToMany "delete parent → delete children", put `CASCADE` on the **child's back-reference FK**
(the FK is the single source of truth; `onDelete` is not declared on `ONE_TO_MANY`).

Boot-time guards (fail-fast): `onDelete` is valid only on TO_ONE; `SET_NULL` requires a nullable FK; a
**cyclic / self-referential `CASCADE`** is rejected (delete such hierarchies — org trees, BOM, category
trees — in application code); a **`CASCADE` chain deeper than `MAX_CASCADE_DEPTH` models** is rejected
(bounds recursion; the error names the full chain); and a `CASCADE` from a **soft-delete parent to a
hard-delete child**, or from a **shared parent to a multi-tenant child**, is rejected (see the matrix
above).

A **timeline** target is allowed: the inbound-FK strategy fires on **entity deletion** (`deleteByIds`,
which removes all slices of the logical id — referencing FKs store that logical id, so RESTRICT counts /
CASCADE deletes / SET_NULL nulls by it, no effective-date resolution involved); slice-level
`deleteBySliceId` keeps the entity alive and deliberately does not trigger it.

### `@OptionSet` ↔ `SysOptionSet`

| `@OptionSet` attribute | Type | Default | `SysOptionSet` column | Notes |
|---|---|---|---|---|
| (enum simple name) | — | — | `optionSetCode` | inferred, no override |
| `name` | String | `""` | `name` | display label; empty → humanized enum name (`TenantStatus`→"Tenant Status") |
| `description` | String | `""` | `description` | **≤512 chars**, parse-time enforced (catalog column width); concise user-facing summary — design notes go in Javadoc |
| (scanner sets) | — | — | `appCode` / `id` | |
| (Studio toggle) | — | — | `deleted` / `optionItems` | runtime aggregation |

### `@OptionItem` ↔ `SysOptionItem`

| `@OptionItem` attribute | Type | Default | `SysOptionItem` column | Notes |
|---|---|---|---|---|
| (`@JsonValue` field value on enum) | — | — | `itemCode` | fallback to `enum.name()` when no `@JsonValue` |
| (enclosing enum simple name) | — | — | `optionSetCode` | inferred |
| `label` | String | `""` | `label` | defaults to humanized constant name (`MULTI_FILE`→"Multi File"); declare explicitly to customize. Omit when it equals the humanized name (and omit the whole `@OptionItem` if nothing else remains) |
| `description` | String | `""` | `description` | **≤512 chars**, parse-time enforced (catalog column width); concise user-facing summary — design notes go in Javadoc |
| `sequence` | int | `-1` | `sequence` | `-1` → use `ordinal() + 1` |
| `parentItemCode` | String | `""` | `parentItemCode` | hierarchy |
| `itemTone` | `OptionItemTone[]` | `{}` | `itemTone` | single element |
| `itemIcon` | `OptionItemIcon[]` | `{}` | `itemIcon` | single element |
| (scanner sets) | — | — | `appCode` / `id` / `optionSetId` | |
| (Studio toggle) | — | — | `active` | |

### `@Index` ↔ `SysModelIndex`

`@Index` is `@Repeatable` — stack multiple declarations on one `@Model` class.

| `@Index` attribute | Type | Default | `SysModelIndex` column | Notes |
|---|---|---|---|---|
| (enclosing class) | — | — | `modelName` | inferred |
| `indexName` (or auto-derived) | String | `""` | `indexName` | `idx_<table>_<col>...` / `uk_<table>_<col>...` for unique; **globally unique** (boot-enforced), ≤60 chars |
| `fields` | String[] | required | `indexFields` | **camelCase Java field names**, not column names |
| `unique` | boolean | `false` | `uniqueIndex` | |
| `message` | String | `""` | `message` | end-user text on a unique-constraint violation (**only when `unique`**); its own i18n key; empty → composed from the member fields' labels |
| (scanner sets) | — | — | `appCode` / `id` | |
| (FK fixup post-init) | — | — | `modelId` | |

**Note**: `@Model.businessKey` does **not** auto-create a UNIQUE index.
Multi-tenant models typically want `UNIQUE (tenant_id, businessKey...)`
which has tenant-aware semantics not expressible by `@Index` alone —
declare such indexes explicitly:
```java
@Index(fields = {"tenantId", "code"}, unique = true)
```

## Runtime catalog identity (`app_code`)

Rows in `sys_model` / `sys_field` / `sys_option_set` / `sys_option_item` /
`sys_model_index` are scoped by **`app_code`**, stamped server-side from
`system.app-code`. The retired `ownership` tier column is gone from
the baseline — the annotation lane and the Studio no-code lane reconcile the
**same rows matched by business key** (`modelName` / `fieldName` /
`optionSetCode` / `itemCode`, plus `renamedFrom`).

In development, packages listed in `scanner-scope` reconcile annotation-derived
metadata into `sys_*` for this app. In production, Studio/connector publish
applies the app-scoped design catalog. Per-tenant runtime metadata
customization is not represented as separate `sys_*` rows.

Verify after boot:

```sql
SELECT model_name, app_code FROM sys_model WHERE app_code = '<system.app-code>';
```

The `Ownership` enum (`io.softa.framework.orm.enums.Ownership`) remains in code
as a reserved business-data concept; nothing reads or writes an `ownership`
column on current runtime `sys_*` tables.

See [`starters/metadata-starter/README.md`](../../starters/metadata-starter/README.md)
for scanner-scope, DDL policy, and `renamedFrom` handling.

## Runtime control annotations
### `@DataSource`
Switch the current method or class to a named datasource.

Behavior:
- Method-level annotation overrides class-level annotation.
- If no datasource is currently bound, the target datasource is used for the method scope.
- If the same datasource is already bound, no switch happens.
- If a different datasource is already bound and a Spring transaction is active, the framework throws an exception instead of switching.

Typical usage:
```java
@DataSource("db1")
public void readFromDb1() {
    // ...
}
```

### `@Debug`
Temporarily sets `Context.debug=true` for the annotated method.

Behavior:
- Enables SQL debug output for methods intercepted by `@ExecuteSql`.
- `ExecuteSqlAspect` logs SQL text, parameters, partial results, and elapsed time while debug mode is enabled.

Typical usage:
```java
@Debug
public List<Map<String, Object>> inspectQuery() {
    return jdbcService.searchList(...);
}
```

### `@SwitchUser`
Clones the current context, replaces the current user name with a system user or alias, and sets
`skipPermissionCheck=true` for the method scope.

Typical usage:
```java
@SwitchUser(SystemUser.CRON_USER)
public void runAsCronUser() {
    // ...
}
```

Notes:
- This is useful for scheduled tasks, system jobs, or framework-level operations.
- It changes the execution context user name, not the datasource or tenant mode.

### `@SkipPermissionCheck`
Temporarily sets `Context.skipPermissionCheck=true` for the annotated method.

Typical usage:
- metadata loading
- internal framework pipelines
- trusted system entry points

### `@RequireRole`
Intended to require a specific `SystemRole` before entering the method.

Current implementation note:
- The role check is still marked `TODO` in `PermissionAspect`.
- Today it mainly behaves like a placeholder wrapper that enables `skipPermissionCheck` after the future role check hook.

### `@SkipAutoAudit`
Temporarily disables automatic audit field filling for the current method scope.

Effect:
- created/updated audit fields are not auto-filled while the method runs.

### `@DataMask`
Toggles response/result masking in the current method scope.

Behavior:
- `@DataMask` or `@DataMask(true)` enables masking.
- `@DataMask(false)` disables masking temporarily, which is useful for trusted internal endpoints.

### `@ExecuteSql` and `@WriteOperation`
These are infrastructure annotations used by the low-level JDBC proxy layer.

Behavior:
- `@ExecuteSql` marks a method as an SQL execution entry point.
- In read-write-separation mode, datasource routing happens around `@ExecuteSql`.
- `@WriteOperation` tells the read/write router that the method must use the primary datasource when not inside a transaction.

Recommendation:
- Most application service methods should not add these annotations directly.
- Use them when building framework-level JDBC wrappers or custom low-level SQL executors.

### Less Common Annotations
- `@RPCCheckpoint`: framework-internal AOP hook used by `JdbcServiceImpl` to
  redirect ORM calls to the app that owns the model — `SwitchServiceAspect`
  routes by the model's `appCode` when it differs from this runtime's
  `system.app-code`. Application services do not apply this annotation themselves.
  See [Service-to-Service RPC](../architecture/rpc.md) for
  the mechanism, wire format, and configuration.
- `@CrossTenant` and `@PerTenant`: covered in the multi-tenancy section below.

## Multi-Tenancy
### Runtime Preconditions
To use shared-db multi-tenancy correctly:
- set `system.enable-multi-tenancy=true`
- mark the model metadata with `multiTenant=true`
- ensure the model contains a `tenantId` field

Startup validation:
- `ModelManager` validates that every `multiTenant=true` model contains `tenantId`
- otherwise startup fails with: `The multi-tenant model {modelName} must contain the tenantId field`

### Default ORM Behavior
When multi-tenancy is enabled and the current context is not cross-tenant:
- reads automatically append `tenant_id = Context.tenantId` for multi-tenant models
- inserts automatically fill `tenantId` from the current context
- non-multi-tenant models are not affected

When `Context.crossTenant=true`:
- tenant filtering is skipped
- tenant auto-fill on insert is skipped

This means cross-tenant writes must set `tenantId` explicitly if you still want to write tenant-owned rows.

### `@CrossTenant`
Use this when a method must run once and see data across all tenants.

Behavior:
- clones the current context
- sets `crossTenant=true`
- sets `skipPermissionCheck=true`
- runs the method once

Typical usage:
```java
@CrossTenant
public void rebuildGlobalStatistics() {
    // ORM reads are not restricted by tenant_id here
}
```

Use cases:
- global reconciliation
- data migration
- admin-wide reporting

### `@PerTenant`
Use this when one method invocation should be expanded into one execution per active tenant.

Behavior:
- requires `TenantInfoService`, which means multi-tenancy must be enabled
- method return type must be `void`
- queries active tenant IDs from `TenantInfoService`
- runs once per active tenant
- sets `tenantId` for each invocation
- sets `skipPermissionCheck=true` for each invocation
- uses virtual threads with max concurrency `100`
- waits for all tenant executions and throws after collecting failures

Typical usage:
```java
@PerTenant
public void syncTenantCache() {
    // Runs once per active tenant with that tenant's context
}
```

Use cases:
- per-tenant scheduled jobs
- tenant-local cache refresh
- tenant-local reconciliation

Important rule:
- Do not combine `@PerTenant` with upstream fan-out that already split work per tenant
  (for example, `cron-starter` with `SysCron.tenantJobMode=PerTenant`), otherwise the job is expanded twice.

## Configuration
### MQ Topic
```yml
mq:
  topics:
    change-log:
      topic: 
```

### Multi-Datasource
Application Scenarios:
* Read-write separation
* Operate multiple databases in one project

#### Multiple Datasource Configuration
The multi-datasource is enabled by configuring `spring.datasource.dynamic.enable = true`.
Otherwise, using the original `spring.datasource.*` as the single datasource.

The first datasource is the default datasource when not specified in annotation. 
The datasource name can be customized in the `application.yml` file.
```yml
spring:
  datasource:
    dynamic:
      enable: true
      # mode: read-write-separation, switch-by-model, multi-tenancy-isolated, multi-datasource(default)
      mode: read-write-separation
      datasource:
        primary:
          driver-class-name: com.mysql.cj.jdbc.Driver
          url: jdbc:mysql://localhost:3306/demo
          username: user0
          password: pass0
        db1:
          driver-class-name: com.mysql.cj.jdbc.Driver
          url: jdbc:mysql://localhost:3306/db1
          username: user1
          password: pass1
        db2:
          driver-class-name: com.mysql.cj.jdbc.Driver
          url: jdbc:mysql://localhost:3306/db2
          username: user2
          password: pass2
```

#### Specify the datasource in Java code
The name of the datasource is the same as the key in the `application.yml` file.
```java
@DataSource("db1")
public void method1() {
    // ...
}
```
Datasource propagation mechanism:
* If the method does not have the DataSource annotation, get the class level annotation.
* If the previous datasource is the same as the current datasource, no need to switch.
* If the previous datasource is different from the current datasource, throw an exception.
* If the previous datasource is null, set the specified datasource as the current datasource.
* If the datasource is set firstly, it will be cleared after the method is executed.

#### Deal with the problem of read-after-write consistency
In the read-write separation scenario, and non-transactional context environment, the read-after-write consistency problem may occur.
The solution is to use the `@DataSource` annotation to specify the datasource for the read operation.
```java
// When 'primary' is the writable datasource.
@DataSource("primary")
public void readMethod1() {
    // ...
}
```

## Timeline Model
A timeline model records historical slices of data over time. It is useful for business data that depends on an effective date (for example, department structures or reports that change before/after a specific date). A business record `id` can have multiple slices; each slice is identified by `sliceId`, and `effectiveStartDate`/`effectiveEndDate` define the effective range.

### 1. Timeline Model Metadata

#### 1.1 Timeline Attribute at Model Level
- `timeline = true` indicates this is a timeline model. It must contain the reserved fields `effectiveStartDate` and `effectiveEndDate`. The system validates these fields on startup and throws an exception if missing.
- `timeline = false` indicates a non-timeline model. Non-timeline models must not define the reserved fields `effectiveStartDate` and `effectiveEndDate`.
- A timeline model **requires an app-generated logical id** — `idStrategy = DISTRIBUTED_LONG` (or
  `DISTRIBUTED_STRING` / `EXTERNAL_ID`). `DB_AUTO_ID` is rejected at boot: the auto-increment lands on the
  physical `sliceId`, so nothing would fill the shared logical `id` column of a first slice (split/correct
  rows arrive carrying the entity's existing id and keep it).

#### 1.2 Primary Keys and Fields
- `sliceId`: physical primary key of a timeline model, used to update a slice.
- `effectiveStartDate`: effective start date of the timeline data.
- `effectiveEndDate`: effective end date of the timeline data.
- `id`: logical (business) primary key, compatible with non-timeline models. All business foreign keys referencing a timeline model use this field.
- If your database needs an auto-increment record number (such as `record_id`) for change logs, you can add it yourself. It is not a framework-reserved field.
- Recommended unique constraint: `(id, effectiveStartDate, effectiveEndDate)` — one index doubles as the
  as-of read cover (the end date is checked in-index) and as an integrity backstop: interval maintenance is
  a check-then-act sequence, so a true concurrent write race on one entity surfaces as a unique violation
  instead of silent same-start slices. Declare it with an **explicit `indexName`** (the default concatenated
  name exceeds the 60-char global limit for longer table names):

  ```java
  @Index(indexName = "uk_<table>_timeline",
         fields = {"id", "effectiveStartDate", "effectiveEndDate"}, unique = true)
  ```

#### 1.3 Metadata Relationships
- Timeline models can relate to themselves via One2One, Many2One, One2Many, Many2Many. Storage and references use the logical primary key `id`.
- When a timeline model relates to a non-timeline model, relation tables store the timeline model logical key `id`.
- When a non-timeline model relates to a timeline model, Many2One/One2One fields and Many2Many join tables store the timeline model logical key `id`.
- Association reads use `effectiveDate` by default (current date if not specified), so there may be no effective slice for the current date.
- In cascade query chains (for example, timeline -> non-timeline -> timeline), `effectiveDate` should be propagated to the last model to keep consistency.

#### 1.4 Cascaded Fields
- Cascaded fields are based on Many2One/One2One associations. When the related model is a timeline model, `Context.effectiveDate` is used to query the related data.

#### 1.5 Timeline Data Concepts
- Every slice must have `effectiveStartDate` and `effectiveEndDate`, and slices for the same `id` are expected to be continuous and non-overlapping.
- To simplify queries, the last slice typically uses `effectiveEndDate = 9999-12-31`.
- In most cases, you only need to set `effectiveStartDate`; the system computes and fills `effectiveEndDate` based on adjacent slices.
- Physical record: each slice is a physical record (identified by `sliceId`). Any change in effective dates creates or updates physical slices. Change logs are bound to physical records.
- Logical record: a group of physical slices that share the same logical `id`. Business foreign keys reference the logical `id`, and association reads return the slice effective on the requested date.

Example timeline slices (same logical department `id`):  

| sliceId (physical) | id (logical) | Department Code | Department Name | effectiveStartDate | effectiveEndDate | Manager |
| --- | --- | --- | --- | --- | --- |  |
| 3 | 6 | D001 | Product R&D Dept | 2022-09-01 | 9999-12-31 | Joan |
| 2 | 6 | D001 | R&D Dept | 2020-05-11 | 2022-08-31 | Tom |
| 1 | 6 | D001 | R&D Dept | 2019-08-01 | 2020-05-10 | Mars |

### 2. Common Scenarios

#### 2.1 Effective Date Propagation
- `effectiveDate` is a `LocalDate` stored in `Context`, defaulting to the current date.
- Query data effective on a specific date:
  `effectiveStartDate <= effectiveDate && effectiveEndDate >= effectiveDate`
- Query data effective within a period (startDateValue, endDateValue must be non-null):
  `effectiveStartDate <= endDateValue && effectiveEndDate >= startDateValue`
- To query all slices for a business record, use `acrossTimelineData()` with `id` filters (or include `effectiveStartDate/effectiveEndDate` in filters).
- Typical adjacent slice lookups:
  `previous: id = {id} AND effective_end_date = {effectiveStartDate - 1}`
  `next: id = {id} AND effective_start_date = {effectiveEndDate + 1}`

#### 2.2 read/search APIs
- Queries like `getById/getByIds/searchList/searchPage` return only slices effective on `effectiveDate` by default.
- To query history across time, use `FlexQuery#acrossTimelineData()` or include `effectiveStartDate`/`effectiveEndDate` in filters.
- Cascaded reads propagate `effectiveDate`.
- **View a record's version list from the REST API**: `/searchPage` (or `/searchList`) with the row's
  `id` in `filters` and `acrossTimeline: true` returns all slices (each carrying its own `sliceId` and
  effective range). Narrow field selections on a timeline model automatically round-trip `sliceId`
  (like `version` under optimistic locking): version rows stay actionable — correct via `update` /
  remove via `deleteBySliceId` — without re-querying. The `acrossTimeline` flag is the explicit half of the dual trigger, exposed on
  `QueryParams` / `SearchListParams`; it is not on `SearchNameParams` (a displayName picker wants the
  as-of option, not every version). Example:
  ```jsonc
  POST /{model}/searchPage
  { "filters": [["id","=",6]], "orders": [["effectiveStartDate","DESC"]], "acrossTimeline": true }
  ```

#### 2.3 create APIs
- For `createOne/createList`, if `effectiveStartDate` is empty, it uses the current `effectiveDate`; if `effectiveEndDate` is empty, it is set to `9999-12-31`.
- If an existing `id` is provided, the system automatically splits or adjusts adjacent slices based on the new `effectiveStartDate`.
- **The three write intents, made explicit:**

  | Intent | API | Key |
  |---|---|---|
  | Create a NEW entity | `create*` **without** `id` | fresh logical `id` + genesis slice |
  | Add a version to an EXISTING entity | `addVersion` (or `create*` with the existing `id`) | returns the new `sliceId` |
  | Correct one existing version | `update*` | keyed by `sliceId` (any supplied `id` is overwritten from the DB) |

- `addVersion(modelName, row)` is the explicit add-version entry (REST: `POST /{model}/addVersion`,
  counterpart of `deleteBySliceId`): the row must carry the existing entity's `id`, and it returns the new
  version's `sliceId` (when the start date matches an existing slice, that slice is corrected in place and
  its `sliceId` is returned). `addVersionAndFetch` also returns the full version row, fetched by `sliceId`
  across the timeline (the new version's effective date may not be today).
- **Guard**: a `create*` call carrying an `id` that matches **no** entity is rejected for
  `DISTRIBUTED_LONG/STRING` models — a typo must not silently mint a new entity with a caller-chosen id.
  Exceptions: `EXTERNAL_ID` models (new entities legitimately arrive with their id) and the
  `enableInsertId` import mode (preset ids).

#### 2.4 update APIs
- The current implementation uses `sliceId` as the update primary key. Updating `effectiveStartDate` automatically corrects adjacent slices' `effectiveEndDate`.
- Manual updates to `effectiveEndDate` are not recommended. To create a new slice, use `create` with an existing `id` and a new `effectiveStartDate`.
- If an upper layer provides a "correct"-style API (update data without creating a new slice), it should locate by `sliceId` (the ORM currently does not provide a dedicated correct API).

#### 2.5 delete / copy APIs
- `deleteById/deleteByIds`: deletes all slices for a business `id` — this is **entity deletion**, and it is
  the point where the inbound-FK delete strategy (`onDelete` RESTRICT / CASCADE / SET_NULL, keyed by the
  logical `id`) fires against referencing models.
- `deleteBySliceId`: deletes a single slice and automatically corrects adjacent slice ranges. The entity
  survives, so `onDelete` deliberately does **not** fire.
- `copyById/copyByIds`: copies the **current (as-of) slice** into a **new entity** — the copyable field set
  excludes every structural timeline key (`id`/`sliceId`/effective dates), so the copy gets a fresh logical
  `id` and a genesis slice at the current date. It does **not** duplicate the full version history, and does
  not add a slice to the source entity. (`businessKey` fields are `copyable = false`, so set a new code on
  the copy.)

#### 2.6 Versioning seam (engine internals)
- All timeline handling in `ModelServiceImpl` routes through one `VersioningStrategy` seam
  (`service/versioning/`): `IdentityStrategy` is a no-op for regular models, `TimelineStrategy` adapts the
  interval-maintenance algorithm in `TimelineService`. New read paths must route Filters/FlexQuery through
  the `scopedRead` exits — there is no per-call-site `if (isTimelineModel)` to forget.
- The across-timeline opt-out is a **dual trigger by contract**: the explicit
  `FlexQuery.acrossTimelineData()` flag, **or** caller-supplied `effectiveStartDate`/`effectiveEndDate`
  conditions (which declare "I am doing my own temporal filtering"). Either suppresses the default
  effective-date clamp; both are intended, stable behavior.
- **Accepted limitations** (a master-detail table split was evaluated and rejected — its headline benefit,
  a real DB FK target, is moot because referential integrity is enforced app-level and no physical FKs are
  emitted): version-invariant fields (e.g. `code`) repeat on every slice, and a **declarative
  reference-by-code relation to a timeline model is not supported** (`code` is not physically unique across
  slices). Reference timeline entities by logical `id` (as-of) or pin one slice via `sliceId`; a **runtime**
  "`code` + effective date" as-of query is fully supported (non-overlapping intervals make it unique).
- `Context.effectiveDate` is ambient state (defaults to today). Batch engines that fan work out across
  threads must propagate the context (ScopedValue) to workers — e.g. a payroll run pricing by `payDate` —
  or that branch silently prices "as of today".

#### 2.7 search Join Rules for Timeline Associations
- When the related object is a timeline model, Many2One/One2One queries automatically append to the `LEFT JOIN ON` clause:
  `effectiveStartDate <= effectiveDate AND effectiveEndDate >= effectiveDate`.
- One2Many/Many2Many cascades also filter slices based on `effectiveDate`.

### Examples

#### 1) Model Definition
```java
@Data
@EqualsAndHashCode(callSuper = true)
@Model(label = "Product Price", timeline = true, idStrategy = IdStrategy.DISTRIBUTED_LONG)
@Index(indexName = "uk_product_price_timeline",
       fields = {"id", "effectiveStartDate", "effectiveEndDate"}, unique = true)
public class ProductPrice extends TimelineModel {
    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Product ID")
    private Long productId;

    @Field(label = "Price")               // BigDecimal → DECIMAL(32,8) by default (money)
    private BigDecimal price;
}
```

#### 2) Query Current and Historical Slices
```java
ContextHolder.getContext().setEffectiveDate(LocalDate.of(2025, 1, 1));

Filters filters = new Filters().eq("productId", 1001L);
List<Map<String, Object>> current = modelService.searchList("ProductPrice", new FlexQuery(filters));

FlexQuery historyQuery = new FlexQuery(new Filters().eq("id", 1L))
        .acrossTimelineData()
        .orderBy(Orders.ofAsc("effectiveStartDate"));
List<Map<String, Object>> history = modelService.searchList("ProductPrice", historyQuery);
```

### 3. Performance
- By default, queries do not scan across time (no `effectiveStartDate/effectiveEndDate` filters and no `acrossTimelineData()`), which reduces scanning.
- Add indexes for `effectiveStartDate` and `effectiveEndDate`.

### 4. Time-Effective (Non-Timeline) Data
Some models need history records with effective dates but are not timeline models (for example, HR changes, work history, education history). These cases may allow multiple records on the same day and do not require continuous slices.

In Softa, timeline fields are reserved. If you need history-only behavior, use a separate history model or different field names, and keep `timeline = false` to avoid timeline slice semantics.
