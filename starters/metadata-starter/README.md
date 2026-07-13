# Softa Metadata Starter

Annotation-driven metadata management for Softa applications. Entities and
their fields are described in Java annotations (`@Model` / `@Field` /
`@OptionSet` / `@OptionItem` / `@Index`); a boot-time scanner reconciles the
annotations with `sys_*` catalog rows and, for the packages listed in
`scanner-scope`, applies the corresponding DDL automatically.

## Quick start

### 1. Add the dependency

```xml
<dependency>
    <groupId>io.softa</groupId>
    <artifactId>metadata-starter</artifactId>
    <version>${softa.version}</version>
</dependency>
```

### 2. Annotate your entity

```java
import io.softa.framework.orm.annotation.*;
import io.softa.framework.orm.entity.AuditableModel;

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

    @Field(label = "Customer Name", required = true, length = 100)
    private String name;

    @Field(label = "Tier")
    private CustomerTier tier;       // enum → FieldType.OPTION (auto-inferred)

    @Field(label = "Status")
    private String status;

    @Field(label = "Email")
    private String email;
}
```

### 3. Set `scanner-scope` in your dev profile (NEVER non-empty in prod)

```yaml
# application-dev.yml
system:
  metadata:
    scanner-scope:
      - "*"          # manage every package; on a shared dev DB, narrow to
                     # your own packages, e.g. ["io\\.acme\\.app.*"]
```

### 4. Boot the app

```
MetadataAnnotationScanner: scanner-scope active (matchAll=true), scanning classpath...
MetadataAnnotationScanner: 12 in-scope @Model class(es), 1 in-scope @OptionSet enum(s) (of 12 / 1 on classpath)
MetadataAnnotationScanner: applied N row change(s) to sys_*
DdlOrchestrator: CREATE TABLE customer OK
DdlOrchestrator: applied 1 DDL statement(s); 0 drop operation(s) deferred to manual SQL
```

Then `SELECT model_name, app_code FROM sys_model WHERE app_code = '<system.app-code>';`
returns the built-in `SYSTEM_MODEL`s plus your annotated entities for this
runtime app.

## The 5 annotations

`@Model` / `@Field` / `@Index` live in `io.softa.framework.orm.annotation`;
`@OptionSet` / `@OptionItem` live in `io.softa.framework.base.annotation` — so
framework enums in `softa-base` (e.g. `Language`) can carry them without a
module cycle.

| Annotation | Target | Purpose |
|---|---|---|
| `@Model` | class | Describes an entity (table, business key, id strategy, multi-tenancy, soft delete, etc.) |
| `@Field` | field | Describes a column (label, type, required, length, related model, etc.) |
| `@OptionSet` | enum class | Marks an enum as a managed option set |
| `@OptionItem` | enum constant | Describes a single option (display name, sequence, tone, icon) |
| `@Index` | class (`@Repeatable`) | Declares a database index (fields, unique, globally-unique name, optional unique-violation message) |

**Key inference rules** (the parser does heavy lifting; you mostly don't
need to specify):

- `modelName` = class `getSimpleName()`
- `fieldName` = Java field name
- `tableName` = `snake_case(modelName)`
- `columnName` = `snake_case(fieldName)`
- `fieldType` = Java type → maps via `TypeInference` table
  (`String → STRING`, `Integer → INTEGER`, `enum → OPTION`,
  `List<enum> → MULTI_OPTION`, `@Model POJO → MANY_TO_ONE`, etc.)
- `optionSetCode` = enum class `getSimpleName()` (always derived; not declarable)
- `itemCode` = `@JsonValue` field/method value on the enum constant
- `OPTION` / `MULTI_OPTION` cannot be written explicitly — only inferred
- Index name = `idx_<table>_<col1>_<col2>...` or `uk_<table>_<col1>_<col2>...`
- Explicit `tableName`, `columnName`, and `indexName` values must satisfy
  `StringTools.isTableOrColumn` and must not be SQL reserved words because DDL
  renders identifiers unquoted.

## Runtime catalog identity (`app_code`)

The runtime `sys_model` / `sys_field` / `sys_option_set` /
`sys_option_item` / `sys_model_index` catalog no longer carries an
`ownership` column. The ownership tier was retired before the current baseline;
see `deploy/migrations/README.md` for the removed V1/V3/V7 migrations.

Every app-scoped runtime metadata row is instead stamped with `app_code` from
`system.app-code`. This is the catalog identity used by scanner writes,
runtime export/apply APIs, checksum comparison, and FK backfill SQL. A single
app may use multiple databases, and multiple apps may share one database, as
long as each app keeps a distinct `app_code` and physical table names do not
collide.

`scanner-scope` remains a package filter for Java annotation reconciliation,
not an ownership tier. In development the scanner materializes the annotated
catalog for this runtime app; in production Studio/connector publish applies
the app-scoped design catalog. Per-tenant runtime metadata customization is not
represented as separate `sys_*` rows.

## `scanner-scope` behavior matrix

`scanner-scope` is a list of regex patterns full-matched against each
`@Model`/`@OptionSet` class's package name. `"*"` (sole entry) = all packages;
empty / unset = manage nothing.

| `system.metadata.scanner-scope` | Scanner runs | DDL execution | Drift detection |
|---|---|---|---|
| `["*"]` | Boot-time, eager, all packages | Auto: `CREATE TABLE` / `ADD COLUMN` / `MODIFY COLUMN` / `ADD INDEX`. **Never auto-DROP** | n/a |
| `["io\\.acme\\.foo.*", …]` | Boot-time, in-scope packages only | Same auto-policy, in-scope models only | n/a |
| empty / unset (default, prod) | n/a | n/a | `MetadataAnnotationChecker` runs post-boot on a virtual thread; logs WARN if code-vs-DB drift detected |

On a **shared dev database**, give each developer a narrow scope (their own
packages) so the scanner only reconciles the Java packages they are actively
changing. Scope is per-package, not per-class, and it is not an ownership
barrier; app identity is still `app_code`, and physical table-name collisions
remain a database-level concern.

**DDL auto-execute policy**:

| Operation | Auto-executed |
|---|---|
| `CREATE TABLE IF NOT EXISTS` | ✅ |
| `ADD COLUMN` | ✅ |
| `MODIFY COLUMN` (type / nullable / length / default) | ✅ |
| `ADD INDEX` | ✅ |
| `DROP TABLE` / `DROP COLUMN` / `DROP INDEX` | ❌ — logs WARN with copy-paste SQL |

Rationale: additive DDL doesn't lose data; `DROP` operations are destructive
and may take minutes on large tables. Even in dev, you should consciously
choose to drop schema.

### Field / model rename — declare `renamedFrom`

The scanner uses **set-based comparison** keyed by `fieldName` / `modelName` /
`itemCode`, so an *undeclared* rename looks identical to "drop old + add new":
ADD COLUMN (auto) + WARN-only DROP → both columns coexist, old keeps the data,
new is NULL = **silent data divorce**.

Declaring the prior name fixes this:

```java
@Field(label = "External ID", renamedFrom = "legacyId")   // single-step; prior fieldName only
private Long externalId;
```

The parser carries the prior name, the `DiffEngine` pairs the two sides into one
`Modification(kind=RENAME)`, and the scanner **auto-executes** `CHANGE COLUMN`
(PostgreSQL: `RENAME COLUMN`) and updates the `sys_field` row in place by its
surrogate `id` — data is moved, not divorced. `@Model(renamedFrom = "OldName")` on
a type auto-executes `ALTER TABLE old RENAME TO new` and cascades `model_name` onto
every `sys_field` / `sys_model_index` row (no field churn). A half-applied rename —
both the new and prior name present — fails fast until resolved manually.

**Still hand-migrate** (`renamedFrom` does not cover these):
- Renaming a `@OptionItem` `@JsonValue` code (item_code + business rows) — explicit
  `UPDATE` migration.
- A rename entangled with a data transform (type change, split/merge).

See the *Manual migrations* section of
[annotation-lane.md](../../docs/ai/framework/annotation-lane.md).

## Configuration

Optional: MQ topic config for change notifications (if `message-starter` is
on the classpath):

```yaml
mq:
  topics:
    inner-broadcast:
      topic: dev_demo_inner_broadcast
      sub: dev_demo_inner_broadcast_sub
```
