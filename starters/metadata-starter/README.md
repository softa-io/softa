# Softa Metadata Starter

Annotation-driven metadata management for Softa applications. Entities and
their fields are described in Java annotations (`@Model` / `@Field` /
`@OptionSet` / `@OptionItem` / `@Index`); a boot-time scanner reconciles the
annotations with `sys_*` catalog rows and, for the packages listed in
`scanner-scope`, **converges the physical schema to the annotations** —
declared changes and hand-made drift alike, so a restart always ends with
schema ≡ annotations for everything the scope owns.

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
DdlOrchestrator: CREATE TABLE customer [converge] genesis OK
DdlOrchestrator: converged 12 in-scope model(s) — executed 1 DDL statement(s), skipped 0 already applied
MetadataAnnotationScanner: applied N row change(s) to sys_*
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
| `@Index` | class (`@Repeatable`) | Declares a database index (fields, unique, globally-unique name, optional unique-violation message, physical `method` intent) |

**Key inference rules** (the parser does heavy lifting; you mostly don't
need to specify):

- `modelName` = class `getSimpleName()`
- `fieldName` = Java field name
- `tableName` = `snake_case(modelName)`
- `columnName` = `snake_case(fieldName)`
- `fieldType` = Java type → maps via `TypeInference` table
  (`String → STRING`, `Integer → INTEGER`, `enum → OPTION`,
  `List<enum> → MULTI_OPTION`, `@Model POJO → MANY_TO_ONE`,
  `DTOFieldObject POJO → DTO`, etc.)
- `TEXT` (unbounded long text — MySQL MEDIUMTEXT / PostgreSQL TEXT) is never
  inferred: declare `@Field(fieldType = FieldType.TEXT)` on a `String` field.
  `length` is optional and only an app-level guard (the column is unbounded).
- `optionSetCode` = enum class `getSimpleName()` (always derived; not declarable)
- `itemCode` = `@JsonValue` field/method value on the enum constant
- `OPTION` / `MULTI_OPTION` cannot be written explicitly — only inferred
- Index name = `idx_<table>_<col1>_<col2>...` or `uk_<table>_<col1>_<col2>...`
- `@Index(method = ...)` is a dialect-neutral physical intent (default `BTREE`):
  `SEARCH` renders a trigram GIN index on PostgreSQL (`pg_trgm` is provisioned by
  the DDL executor; it is a trusted extension since PostgreSQL 13, so the app's
  database-owner role can create it) and a plain index on MySQL; `PREFIX` renders
  a `text_pattern_ops` B-tree on PostgreSQL (prefix `LIKE` takes a range scan on
  any collation) and a plain index on MySQL. Both require exactly one
  `STRING` / `TEXT` / `MULTI_STRING` field and reject `unique = true` at scan time.
- **Search indexes are derived, not declared**: `SearchIndexSynthesizer` (invoked from
  `MetadataReadPipeline.parse`, so the scanner and the production checker see the same
  from-code state) derives one `SEARCH` index per `@Model(searchName)` member — falling
  back to a `STRING` field literally called `name` — named
  `idx_<table>_<column>_search` (deterministically shortened past 60 chars). Dynamic,
  non-`STRING` fields, projections and non-RDBMS models are skipped; a hand-written
  `@Index` with an explicit non-BTREE `method` on the column suppresses the derivation
  for that column. The studio lane derives the identical rows while assembling its
  desired state (`DesignSearchIndexSpecs`), so a deploy never deletes them as
  runtime-only. When PostgreSQL cannot build a trigram index (no `pg_trgm`, and the
  role may not create it), the planner skips those indexes per-index with one
  actionable WARN instead of failing the boot — the rows stay in `sys_model_index`,
  so the first boot after a DBA runs `CREATE EXTENSION pg_trgm;` creates them.
- Explicit `tableName`, `columnName`, and `indexName` values must satisfy
  `StringTools.isTableOrColumn` and must not be SQL reserved words because DDL
  renders identifiers unquoted.

### Projection models (shared / external tables)

`@Model(projection = true)` marks a **read-only model over a table it does not
own** — either another model's table (a report exposing a slice of `Employee`'s
columns plus its own `dynamic` computed fields) or a table created outside the
scanner entirely (e.g. by a BI pipeline). Its metadata is independent: declare
the fields it exposes exactly like any model (`tableName` optional — derived as
usual when omitted). What changes is ownership:

- **No DDL, ever.** The scanner writes / updates its `sys_*` rows but renders no
  CREATE / ALTER / RENAME for it; removing the model hints no DROP TABLE (the
  table must survive), and a `tableName` change is a re-point, not a rename.
- **One table, one owner.** Every non-projection RDBMS model claims exclusive
  DDL ownership of its resolved table; two owners on one `tableName` fail at
  parse. This is what makes a fresh-database bootstrap deterministic (exactly
  one CREATE per table) and turns an accidental table-name collision between
  unrelated models — previously a silent table merge — into a boot error.
- **Read-only.** The write APIs (create / update / delete / copy) reject a
  projection; `MetaModelDTO.projection` lets the UI hide the actions. Writing
  through a subset model would bypass the owner's required-field validation.
- **No `@Index`.** Indexes belong to the table's owner; declaring one on a
  projection is boot-rejected. RDBMS storage only.
- **One-way physical audit.** The drift audit still checks that the columns a
  projection declares physically exist (missing column / type mismatch stay
  reported), but the table's other columns and indexes are the owner's business —
  never "undeclared" noise. A physically **missing table** logs an **ERROR**
  (queries on the model will fail) but never fails the boot and is never
  auto-created: its creation deliberately belongs to the owner model or the
  external process.

Convention for in-app sharing: keep a projection's stored-field declarations
byte-identical to the owner's columns it exposes (type / length / required),
and make everything else `dynamic` — the shared table then has exactly one
source of physical truth.

## Field constraints (`sys_field.constraints`)

`@Field(min / max / pattern / constraintMessage / requiredWhen / hiddenWhen / readonlyWhen /
invalidWhen)` are packed by `AnnotationParser` into one `FieldConstraints` record and stored in the
single `sys_field.constraints` column (`FieldType.DTO`, canonical JSON via `Codecs.dto` +
`CanonicalJson`; `NULL` when nothing is declared). The parser validates the declaration against the
field's type and the sibling fields it names once the class is fully parsed (boot failure on a
mistake); `ModelManager` runs the same `FieldConstraints.validate` on load for studio / hand-written
rows and drops a bad one with an ERROR instead of failing the boot — a row whose JSON does not even
parse is read as "no constraints" the same way. `design_field.constraints` is the structural twin the
cross-lane checksum requires (`SysDesignAttributeParityTest`). Enforcement and the expression language
are documented in [framework/softa-orm/README.md](../../framework/softa-orm/README.md) §Field
constraints. The `sys_field` column self-applies at boot under a non-empty `scanner-scope` (the
catalog reconcile runs before the strict read). An empty-scope environment — the production shape —
and `design_field`, which no deployment here keeps in scope, need the column added however that
deployment applies catalog DDL.

## Runtime catalog identity (`app_code`)

The runtime `sys_model` / `sys_field` / `sys_option_set` /
`sys_option_item` / `sys_model_index` catalog no longer carries an
`ownership` column. The ownership tier (and its V1/V3/V7 migrations) was
retired before the current baseline.

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
| `["*"]` | Boot-time, eager, all packages | **Physical convergence** (see below): every owned table converges to its annotations on every boot — CREATE / ADD / MODIFY (narrowing included) / declared RENAME, plus **DROP of undeclared columns and indexes** | Code-less catalog roots named in a WARN with copy-paste SQL; the drift audit reports the residual (projections, undeclared tables) |
| `["io\\.acme\\.foo.*", …]` | Boot-time, in-scope packages only | Same convergence, in-scope models only — out-of-scope tables are never touched | n/a |
| empty / unset (default, prod) | n/a | n/a | `MetadataAnnotationChecker` runs post-boot on a virtual thread; logs WARN if code-vs-DB drift detected, audits the physical schema against `sys_*` (see Physical convergence — read-only here), and records the `GET /metadata/status` snapshot |

On a **shared dev database**, give each developer a narrow scope (their own
packages) so the scanner only reconciles the Java packages they are actively
changing. Scope is per-package, not per-class, and it is not an ownership
barrier; app identity is still `app_code`. Table-name collisions between two
models parsed **together** fail at boot (see Projection models — one table, one
owner); collisions across separately-scoped parses or separate apps remain a
database-level concern.

### Catalog row policy (what the scanner writes to `sys_*`)

The catalog is an aggregate: `sys_model` / `sys_option_set` are the **roots**, `sys_field` /
`sys_model_index` / `sys_option_item` their attributes.

| Change | Applied |
|---|---|
| Root added / modified (model, option set) | ✅ |
| Attribute added / modified / **removed**, on a root whose class is present | ✅ — the annotations own the root's attribute set |
| **Root removed** (a `sys_model` / `sys_option_set` row with no `@Model` / `@OptionSet` class) | ❌ under **every** scope, `["*"]` included — the root and all its attribute rows are left untouched; `["*"]` logs a WARN naming them with copy-paste `DELETE` SQL |

Rationale, and why this differs from a Java-class deletion being "obvious drift": a code-less root
is a **first-class** state here — the Studio no-code lane and metadata seed files author models
that never have a Java class, and nothing in the catalog records row ownership (the `Ownership`
enum is retained but unused). So "orphan" and "deliberately code-less" are indistinguishable, and
auto-deleting would silently destroy hand-authored definitions on every boot. Note the contrast
with the physical schema: an undeclared **column** on a table the scope owns has no legitimate
author (the owner's annotations are its single source of truth) and is converged away, while a
code-less **root** may be someone's deliberate definition and is only ever named in the WARN.

**DDL execution policy** — the physical schema of every in-scope owned table is a pure
function of the annotations. With physical facts available (the normal case) the scanner runs
**physical convergence**:

| Drift / change | Convergence action |
|---|---|
| table missing | `CREATE TABLE` from the full code definition (genesis); a pre-existing table is adopted column-by-column instead |
| declared column missing | `ADD COLUMN`; `CHANGE COLUMN` when a rename pairing's prior column physically exists |
| declared attribute change (type / length / required / default / comment) | `MODIFY COLUMN` re-stating the declared shape |
| physical type/width mismatch — widen, **narrow**, incomparable | `MODIFY COLUMN` to the declared shape. Narrowing executes here: the declaration is the truth, and a non-empty `scanner-scope` is by definition non-production |
| **undeclared column / index** on an owned table | **`DROP`** — drift is eliminated, not reported |
| declared index missing / definition changed | `ADD INDEX` / rebuild (DROP + ADD) |
| bare `tableName` change while the old table physically exists | boot **fails** with instructions — creating the new table would silently divorce the data, and the planner never guesses (declare a model `renamedFrom`, or rename manually first) |
| whole undeclared **tables**; anything on a **projection** | untouched — ownership cannot be proven (another `app_code`, a legacy table) / belongs to the owner; the drift audit is the reporting channel |

When physical introspection is unavailable the boot degrades to the conservative
**metadata-only lane**: it plans purely from the `sys_*` diff — additive changes and declared
renames auto-execute; DROPs, bare `tableName` changes and anything else destructive defer to a
warn-only copy-paste SQL block, because without facts drift and intent are indistinguishable.

Rationale: the audit and the convergence engine share one comparator (`PhysicalTypeCompat`) and
one index-name matcher (`IndexNameCompat`), so what the audit would report is exactly what a
converging boot eliminates — after any restart, schema ≡ annotations for everything the scope
owns. Destructive verbs can never reach production because production runs the empty scope
(checker-only, report-not-act); the gate is the existing `scanner-scope` posture, not a new
switch. `DROP` / `MODIFY` on large tables can still lock for minutes — another reason a
non-empty scope stays out of production.

### Catalog self-bootstrap (the `sys_*` tables' own schema)

The five boot-read catalog tables (`sys_model`, `sys_field`, `sys_option_set`,
`sys_option_item`, `sys_model_index` — `SysCatalog.BOOT_READ_ENTITIES`) have no
row-level "last applied state": the rows that record every other model's state
live *inside* them. Their only baseline is the physical schema, so on every
boot with a non-empty `scanner-scope` the scanner reconciles them **physically,
from their own annotations, before the strict catalog read**
(`DdlOrchestrator.reconcilePhysical`, log tag `[catalog]`):

| Annotation vs physical | Action |
|---|---|
| table missing | `CREATE TABLE` from the code definition (a fresh database bootstraps with **no baseline DDL**) |
| column missing | `ADD COLUMN` (a catalog-column addition no longer needs a migration — the old chicken-and-egg) |
| column missing, field declares `renamedFrom`, prior column present | `CHANGE COLUMN` — data carried |
| prior **and** new columns both present under a declared rename | boot fails with instructions (half-applied rename; never guessed) |
| column physically narrower than declared (bounded widths only) | `MODIFY COLUMN` widen. Declared-unbounded columns (TEXT/JSON/DTO) never trigger on width — engines report their width inconsistently and the same MODIFY would re-plan every boot |
| column wider / incomparable, undeclared physical columns | untouched by **this stage** — it runs before the diff exists and possibly under a narrow scope that does not manage the catalog, so it may only grow the schema. When the catalog packages are in scope, the main convergence pass later in the same boot eliminates these like any other in-scope drift; otherwise the drift audit is the reporting channel |
| declared index missing | `ADD INDEX` |

After the reconcile the strict read is structurally guaranteed to succeed
(its SELECT set ⊆ physical columns). What still needs a migration: **backfill
`UPDATE`s** that give an added column real values on rows the scanner does not
manage (narrow scopes), destructive changes on **out-of-scope** catalog tables,
and environments running the empty-scope checker posture — there nothing
auto-applies, catalog included.

The whole boot DDL window (catalog reconcile → strict read → diff → DDL → row
writes) is serialized across instances by a database session lock
(`BootDdlLock`: MySQL `GET_LOCK` / PostgreSQL advisory lock on a dedicated
connection, 60s wait budget; a timeout fails the boot rather than queueing
instances behind a wedged sibling).

### Physical convergence

The convergence planner needs facts: the orchestrator snapshots the managed tables via
`DatabaseMetaData` on every boot (the drift audit needs it too). The snapshot costs a
constant number of round trips regardless of model count — tables and columns are each one
catalog-wide metadata pass filtered in memory, index names one dialect query (MySQL /
PostgreSQL; other engines fall back to one `getIndexInfo` per table). It is logged as
`physical snapshot = N managed table(s)`. There is no switch for any of this — posture
follows `scanner-scope` alone (active scope converges, empty scope audits read-only), and
an introspection failure degrades gracefully to the metadata-only lane described above.

The planner reads the **verb** from the physical facts and the **deltas introspection
cannot see** from the `sys_*` diff: declared-rename pairings with their exact prior
column/table names, attribute changes (NOT NULL / DEFAULT / COMMENT), and index-definition
changes behind an unchanged name. Because the verb comes from the facts, drift heals whether
or not a diff exists — a hand-dropped column re-adds on the next boot, a hand-dropped table
recreates in full, a pre-existing table behind a fresh model is adopted, and a declared
rename whose prior column still lingers physically carries the data with `CHANGE COLUMN`
even when the `sys_*` rows were already updated elsewhere (the partial-restore /
cross-environment-import wound). Statements classify per unit on execution: a target that
vanished between snapshot and execution degrades to an already-applied WARN, while genuine
SQL errors fail the boot with the rows unwritten (the next boot replans the same state).

Type comparison is by `java.sql.Types` **equivalence class** on both sides (the
declared side through `FieldType.getSqlType()` / the TO_ONE FK's resolved
mirror; the observed side through the same reverse map the studio JDBC
connector uses) — never by parsing engine type-name strings. Within a class,
widths compare numerically (declared TEXT/JSON/DTO and physical TEXT/CLOB count
as unbounded — declared-unbounded columns never trigger on width, because engines
report their width inconsistently and the same MODIFY would re-plan every boot);
`INTEGER ⊂ LONG ⊂ BIG_DECIMAL` orders the numeric lattice; a declared BOOLEAN
accepts an integer-class column (MySQL renders BOOLEAN as TINYINT). Index names
match through `IndexNameCompat` (exact, or an engine-mangled synthetic variant),
by both the audit and the planner.

**Whole-catalog drift audit + `GET /metadata/status`**: after convergence executes, the
scanner re-snapshots and audits, so the boot log's drift report is the **residual** —
projection tables someone else must create, out-of-scope catalog drift, whole undeclared
tables — never work the scope owns but left undone. Under the empty scope the checker
audits `sys_*` vs physical read-only and the report is the whole drift. The boot snapshot —
code vs catalog fingerprints (SHA-256 over the per-aggregate checksums the studio handshake
uses) plus the drift report — is served by `GET /metadata/status`, so "did my
change reach this runtime?" is one call.

### Field / model rename — declare `renamedFrom`

The scanner uses **set-based comparison** keyed by `fieldName` / `modelName` /
`itemCode`, so an *undeclared* rename looks identical to "drop old + add new" —
and under an active scope the convergence pass executes exactly that: the new
column is added empty, and the old column — no longer declared by anything —
is **dropped together with its data** in the same boot. (On the metadata-only
fallback lane the DROP stays warn-only and the data lingers in the orphaned
column instead.) Either way, nothing arrives in the new column.

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
