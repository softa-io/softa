> **Framework-contributor version** (this monorepo). This is the **internal
> overlay** — how the scanner processes the annotations, the DDL policy, the
> in-repo verification recipe, and manual migrations. For **how to write** the
> `@Model` / `@Field` / `@Index` / `@OptionSet` / `@OptionItem` annotations,
> read [`../authoring/entities.md`](../authoring/entities.md) first — this doc
> does not repeat it.

# Annotation Lane — Framework-Contributor Notes

## Scope (editing in this repo)

**Edit:**
- `starters/<starter>/src/main/java/.../entity/*.java` — model classes
- `starters/<starter>/src/main/java/.../enums/*.java` — option-set enums
- sometimes `apps/<app>/src/main/java/.../entity/*.java` for app-specific models

**Do NOT touch:**
- `sys_*` DB rows directly (the scanner owns them)
- `application*.yml` (that's [app-config](../authoring/config.md))
- `data-system/*.json` seed files (that's [seed-data](../authoring/seed-data.md))
- `framework/` internals (almost never the right place)
- Studio `Design*Controller` endpoints (that's [studio-no-code](../studio-no-code.md))

**Your output is a git diff.** Nothing here writes to a database. The
`MetadataAnnotationScanner` reads your annotations at boot and reconciles the
`sys_*` rows.

## What the scanner does

When a model's package is in `system.metadata.scanner-scope`, the scanner
reconciles annotation-derived metadata into `sys_model` / `sys_field` /
`sys_option_set` / `sys_option_item` / `sys_model_index` and auto-applies DDL.
Rows are matched by **business key** (`modelName` / `fieldName` /
`optionSetCode` / `itemCode`, plus `renamedFrom`) and are app-scoped by
`app_code`, stamped server-side. There is no per-channel duplication and no
`ownership` tier column — the annotation lane and the Studio no-code lane
reconcile the same rows by business key.

**Boot ordering / convergence:** the scanner executes DDL **before** committing
the `sys_*` rows — a failed DDL leaves the rows unwritten, so the next boot
replans and retries (re-applied DDL degrades to WARN on "already exists"). DDL
planning snapshots the managed tables via `DatabaseMetaData` and **converges
every in-scope owned table to its annotations** — the verbs come from
annotations vs physical facts, the `sys_*` diff contributes only what
introspection cannot see (rename pairings, NOT NULL / DEFAULT / COMMENT deltas,
index-definition changes). Hand-made drift therefore heals with or without a
metadata diff: dropped columns/tables recreate, pre-existing tables are adopted,
undeclared columns/indexes drop, mismatched shapes modify to the declared shape.
Introspection failure degrades to conservative metadata-only planning (additive
auto, destructive warn-only). Every boot then logs the **residual** drift audit,
and `GET /metadata/status` serves the boot snapshot (code vs catalog
fingerprints + the drift report) — details in the
[metadata-starter README](../../../starters/metadata-starter/README.md).

## Catalog row policy (`sys_*` rows)

The catalog is an aggregate: `sys_model` / `sys_option_set` are the **roots**,
`sys_field` / `sys_model_index` / `sys_option_item` their attributes.

| Change | Applied |
|---|---|
| Root added / modified | ✅ |
| Attribute added / modified / **removed**, on a root whose class is present | ✅ — the annotations own the root's attribute set |
| **Root removed** (a row with no `@Model` / `@OptionSet` class) | ❌ under **every** scope, `["*"]` included — the root and its attribute rows are confined out of the diff, untouched; `["*"]` logs a WARN naming them with copy-paste `DELETE` SQL |

So deleting a `@Model` class does **not** clean its catalog rows: expect the WARN
and run the SQL yourself. The reason is that a code-less root is a first-class
state here — Studio no-code and seed-authored models never have a Java class,
nothing records row ownership (`Ownership` is retained but unused), so "orphan"
and "deliberately code-less" are indistinguishable and auto-deleting would wipe
hand-authored definitions on every dev boot. Attributes follow their root so a
definition is never left as a headless or field-less husk. Implementation:
`ScannerScope#confineFromDb` (no match-all short-circuit) +
`MetadataAnnotationScanner#warnCodelessRoots`.

## DDL execution policy (physical convergence)

When the model's package is in `system.metadata.scanner-scope` (e.g.
`scanner-scope: ["*"]`), the physical table is a pure function of the
annotations — every boot converges it, declared changes and hand-made drift
alike:

| State (annotation vs physical) | DDL | Executed? |
|---|---|---|
| New `@Model` class / table physically missing | `CREATE TABLE …` (with inline indexes); a pre-existing table is adopted column-by-column instead | ✅ |
| New `@Field` / declared column physically missing | `ALTER TABLE … ADD COLUMN …` | ✅ |
| Changed `@Field` attribute (length / required / type / default / comment) | `ALTER TABLE … MODIFY COLUMN …` | ✅ |
| Physical type/width mismatch — widen, **narrow**, incomparable | `MODIFY COLUMN` to the declared shape | ✅ — the declaration is the truth; a non-empty scope is by definition non-production |
| Removed `@Field` / **undeclared physical column** | `DROP COLUMN` | ✅ |
| **Undeclared physical index** | `DROP INDEX` | ✅ (primary-key backing indexes excluded) |
| New `@Index` / declared index physically missing | `ALTER TABLE … ADD INDEX …` | ✅ |
| Removed `@Model` | `DROP TABLE` | ❌ — the code-less root keeps its rows *and* its table; the `["*"]` WARN prints the cleanup SQL |
| Changed `tableName` (no `renamedFrom`) while the old table physically exists | — | ❌ **boot fails** with instructions: rename manually first, or declare a model rename — creating the new table would silently divorce the data |

Caveat: this table describes the normal boot (physical introspection
succeeded). If introspection fails, the boot degrades to metadata-only
planning — additive changes auto-execute, every destructive verb defers to a
warn-only copy-paste SQL block.

**Projection models are outside this table entirely**: a model declared
`@Model(projection = true)` (read-only over a table it does not own) generates
**no DDL for any change** — its `sys_*` rows still reconcile, but the table's
shape belongs to its owning model (or an external process). One table has ONE
non-projection owner; a second owner fails at parse. A physically missing
projection table logs an ERROR (never auto-created, never a boot failure). See
the metadata-starter README §Projection models.

## Verification recipe (in-repo)

```bash
# 1. Compile (type errors, wrong imports, bad relatedModel class refs)
mvn -B -ntp -DskipTests compile -pl <affected module> -am

# 2. Parser / annotation semantics
mvn -B -ntp test -pl starters/metadata-starter \
    -Dtest='AnnotationParserTest,SystemModelAnnotationTest,ReferenceDataAnnotationTest,TypeInferenceTest'

# 3. The module's own tests (don't skip Service tests)
mvn -B -ntp test -pl <affected module>

# 4. Read the diff
git diff -- '<affected files>'
git diff main...HEAD -- '**/*.java'      # full PR scope

# 5. (If a scanner-scope: ["*"] app exists) restart and watch:
#   DdlOrchestrator: CREATE TABLE ... OK / ADD COLUMN ... OK / DROP COLUMN ... [converge] undeclared OK
#   DdlOrchestrator: converged N in-scope model(s) — executed X DDL statement(s), ...
#   MetadataAnnotationScanner: applied N row change(s)
#   MetadataAnnotationScanner: physical schema drift — N finding(s) ...   (RESIDUAL audit; absent = converged clean)
# 6. GET /metadata/status — code vs catalog fingerprints ("did my change land?")
#    + the physical drift report from this boot.
```

The full suite is green — any test failure is a real regression, not
pre-existing noise.

## Manual migrations (renames `renamedFrom` can't express)

Single-step `renamedFrom` (see [entities.md](../authoring/entities.md)) covers
ordinary field/model renames. Write a **hand-written migration that runs before
the annotation change** for the cases it can't express:

- An `@OptionItem` `@JsonValue` **itemCode** rename — `sys_option_item.item_code`
  plus every business row storing the old code need an explicit `UPDATE`.
- A **skipped-version / chained** rename (A→B→C where a target env is still on A).
- A rename **entangled with a data transform** (type change, split/merge column).
- A rename touching an **`autoSequence` field or its model** — the binding code
  `"<Model>.<field>"` in the per-tenant `sys_sequence` rows is business data the
  scanner never rewrites; it WARNs with the exact
  `UPDATE sys_sequence SET code = '<new>' WHERE code = '<old>';` instead
  (`SysJdbcWriter` rename advisories). Until that runs, inserts on the renamed
  field fail with `SequenceNotFoundException` (fail-closed by design).

```sql
-- release script of the deploying app (softa keeps no migration files),
-- run before the new jar boots, e.g. NN-before-jar-rename-customer-legacy-id.sql
ALTER TABLE customer CHANGE COLUMN legacy_id external_id BIGINT NULL;
UPDATE sys_field SET field_name = 'externalId', column_name = 'external_id'
 WHERE model_name = 'Customer' AND field_name = 'legacyId';
```

Apply it (with DBA review on shared/prod), **then** change the annotation — the
scanner then sees no diff and emits zero DDL. Never rename a `@Field`/`@Model`
without either `renamedFrom` or a migration: an active `scanner-scope` treats the
old column as undeclared and **drops it, data included, on the next boot**;
checker-only environments strand the data in an orphaned column instead.

## Framework-internal notes & pitfalls

- **Cross-module / framework enums.** A framework enum in `softa-base` (e.g.
  `Language`) carries `@OptionSet` from `io.softa.framework.base.annotation` — it
  **cannot** depend on `softa-orm` annotations (module cycle). A field typed as
  such an enum emits a `sys_field` row with the right `optionSetCode`; the
  matching `sys_option_set` row is seeded separately (DML), not by the scanner.
- **`Sys*Trans` tables have no `ownership` column** — and there is no `ownership`
  tier column on the runtime `sys_*` catalog at all (the `Ownership` enum is
  retained but unused). Don't add one.
- **Don't edit the `sys_*` SQL DDL files** for a column already described by
  `@Field` — the scanner manages its DDL; hand-editing creates drift.
- **No `serviceName` / per-model routing flag** — cross-app routing is automatic
  by the model's server-stamped `appCode`. `@Model` has no routing attribute.
- **No `@Schema` on entities** — `@Model` / `@Field` are the single metadata
  source; OpenAPI is generated from them.
- **Write a `<Model>AnnotationTest`** when you add a new `@Model` (pattern:
  `ReferenceDataAnnotationTest`). There is no `metadata-test gen` cli.
- **App-config / seed-data internals** (their authoring lives in
  [../authoring/config.md](../authoring/config.md) /
  [../authoring/seed-data.md](../authoring/seed-data.md)): the scanner reconciles
  `sys_*` against the **primary** datasource only (even for `@Model(dataSource=…)`
  models — only their business table goes elsewhere); seed data is business rows
  that seed-starter loads from the files in the app's `seed-manifest.yml` (platform
  files on the admin's load, tenant files once per tenant), distinct from Studio
  no-code rows.

## When to escalate (don't auto-fix)

Surface these to a human rather than quietly applying:

- Breaking change to an `@JsonValue` value or enum constant name (API contract).
- Renaming a `@Model` class (changes `modelName`; seed data / no-code rows reference it).
- Changing `idStrategy` on an existing model (changes the `id` column type — needs data migration).
- Adding `multiTenant = true` to an existing model (adds `tenant_id`; existing rows need a default tenant).
- Removing/renaming a column that Studio no-code definitions reference.
- A Java field type the inference table doesn't cover (raw `Map`, custom value type).

Scanner lifecycle invariant: the scanner runs **once at boot** (a `MetadataInitializer`
executed before `ModelManager.init()`). The runtime metadata reload paths — inner-broadcast
consumer and the reload cron — only re-read `sys_*` into caches and **deliberately never re-run
the scanner**, so a cache reload can never re-trigger `ALTER TABLE` at runtime.

Structural-fields (id / audit / timeline) rules: `id` is always the managed PK,
and audit/timeline fields carry `@Field` on the base class (`AuditableModel` /
`TimelineModel`) — see the "Key inference rules" in [`CLAUDE.md`](../../../CLAUDE.md).
