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

**Boot ordering / recovery:** the scanner executes DDL **before** committing the
`sys_*` rows — a failed DDL leaves the rows unwritten, so the next boot
recomputes the same diff and retries (re-applied DDL degrades to WARN on
"already exists").

## DDL auto-execute policy

When the model's package is in `system.metadata.scanner-scope` (e.g.
`scanner-scope: ["*"]`):

| Annotation change | DDL | Auto-executed? |
|---|---|---|
| New `@Model` class | `CREATE TABLE …` (with inline indexes) | ✅ |
| New `@Field` | `ALTER TABLE … ADD COLUMN …` | ✅ |
| Changed `@Field` attribute (length / required / type / default) | `ALTER TABLE … MODIFY COLUMN …` | ✅ |
| New `@Index` | `ALTER TABLE … ADD INDEX …` | ✅ |
| Removed `@Field` | `DROP COLUMN` | ❌ **WARN only** — log prints copy-paste SQL |
| Removed `@Model` | `DROP TABLE` | ❌ **WARN only** |
| Removed `@Index` | `DROP INDEX` | ❌ **WARN only** |
| Changed `tableName` | `RENAME TABLE` | ❌ **WARN only** (hint logged) |

If the log shows `WARN: … DROP … not auto-executed`, run the printed SQL by hand
after confirming intent.

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
#   MetadataAnnotationScanner: applied N row change(s)
#   DdlOrchestrator: CREATE TABLE ... OK        (auto)
#   DdlOrchestrator: ... DROP COLUMN ... not auto-executed   (WARN)
```

**Known pre-existing failures to ignore** (unrelated to annotation work):
- 3 in `MetadataServiceImplTest`
- 4 in `PebbleSqlTemplateWhitespaceTest`
- 4 in `DesignAppEnvServiceImplTest`

New failures elsewhere are real regressions.

## Manual migrations (renames `renamedFrom` can't express)

Single-step `renamedFrom` (see [entities.md](../authoring/entities.md)) covers
ordinary field/model renames. Write a **hand-written migration that runs before
the annotation change** for the cases it can't express:

- An `@OptionItem` `@JsonValue` **itemCode** rename — `sys_option_item.item_code`
  plus every business row storing the old code need an explicit `UPDATE`.
- A **skipped-version / chained** rename (A→B→C where a target env is still on A).
- A rename **entangled with a data transform** (type change, split/merge column).

```sql
-- deploy/migrations/mysql/V_N__rename_..._with_data.sql
ALTER TABLE customer CHANGE COLUMN legacy_id external_id BIGINT NULL;
UPDATE sys_field SET field_name = 'externalId', column_name = 'external_id'
 WHERE model_name = 'Customer' AND field_name = 'legacyId';
```

Apply it (with DBA review on shared/prod), **then** change the annotation — the
scanner then sees no diff and emits zero DDL. Never rename a `@Field`/`@Model`
without either `renamedFrom` or a migration → silent data divorce in staging/prod.

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
  models — only their business table goes elsewhere); seed data is **platform-wide**
  (same for all tenants), distinct from Studio no-code rows.

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
