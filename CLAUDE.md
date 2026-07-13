# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> **Where to put new docs**: see [`docs/README.md`](docs/README.md) for the
> documentation conventions. Rationale lives inline where the rule is stated
> (module READMEs, this file, `docs/ai/`); there is no ADR archive — decision
> history is git history, and undecided questions live in

## Project Overview

**Softa** is a metadata-driven, open-source enterprise application development framework (Java 25, Spring Boot 4.1.0) with an integrated ERP system. It is published to Maven Central and GitHub Packages.

## Build & Test Commands

```bash
# Full build (all modules, with tests)
mvn clean install

# Build skipping tests
mvn -B -ntp -DskipTests clean install

# Run all tests
mvn test

# Run tests for a single module
mvn test -pl framework/softa-base

# Run a specific test class
mvn test -pl framework/softa-base -Dtest=PlaceholderUtilsTest

# Verify with JaCoCo coverage
mvn clean verify

# Run a specific app locally
mvn spring-boot:run -pl apps/demo-app

# Publish to GitHub Packages
mvn -DskipTests -Pgithub deploy

# Publish to Maven Central
mvn -DskipTests -Pcentral deploy
```

## Module Structure

The project is a three-tier Maven multi-module build:

```
softa-parent (root pom.xml)
├── framework/          # Core — must be built before starters or apps
│   ├── softa-base      # Utilities, i18n, security controls, PlaceholderUtils, ActuatorController
│   ├── softa-orm       # ORM abstraction, MySQL/PostgreSQL, Pulsar messaging, MinIO/OSS, CosID
│   └── softa-web       # Spring WebMVC REST layer
├── starters/           # Optional, composable feature modules
│   ├── metadata-starter         # Metadata-driven entity/field definitions
│   ├── ai-starter               # LLM integration via Spring AI 2.0 (OpenAI/Azure/OpenAI-compatible, DeepSeek, Anthropic)
│   ├── flow-starter             # Workflow engine
│   ├── cron-starter             # Scheduled task execution
│   ├── es-starter               # Elasticsearch CRUDQ
│   ├── file-starter             # File management
│   ├── message-starter          # SMS / Mail + Pulsar MQ producers/consumers
│   ├── reference-data-starter   # ISO 3166-1 countries, ISO 4217 currencies, ISO 3166-2 subdivisions
│   ├── studio-starter           # Metadata control plane: cross-env governance + DDL rendering (Pebble)
│   ├── user-starter             # User management
│   └── tenant-starter           # Multi-tenancy
└── apps/               # Example applications
    ├── demo-app        # Full-featured demo of all capabilities
    └── mini-app        # Minimal starter app
```

Dependency direction: `framework` → `starters` → `apps`. Each starter declares `<scope>provided</scope>` or `<optional>true</optional>` on framework deps to keep the deployment footprint small.

## Key Architectural Concepts

**Metadata-driven**: Entities and their fields are described in metadata rather than hard-coded schemas. The `metadata-starter` handles this; `studio-starter` uses Pebble templates to generate DDL from metadata (business code is not generated — the runtime is annotation/scanner-driven).

**Global Placeholder Syntax**: `{{ expr }}` is used uniformly across all runtime and build-time contexts:
- Runtime: `PlaceholderUtils` / `TemplateEngine` (softa-base)
- Expressions: AviatorScript 5.4.3 (metadata-starter computed fields)
- DDL generation: Pebble Template Engine (studio-starter)

**Flow Engine**: `flow-starter` provides a node-based workflow system. Flows are defined in JSON and executed by the `FlowEngine`, which supports conditional logic, parallel execution, and integration with other starters (e.g. database operations, messaging).

**ORM Layer**: `softa-orm` wraps Spring Data with multi-database support. ID generation uses CosID (distributed IDs). Object storage is abstracted over MinIO and Aliyun OSS.

## Metadata Governance (Annotation-Driven)

Platform-level
metadata is declared via Java annotations on entity classes. The
`MetadataAnnotationScanner` (in `metadata-starter`) reconciles annotations
with `sys_*` rows at boot for the packages named in `scanner-scope`,
auto-applying the corresponding DDL changes. Platform **no-code** definitions
live in the same `sys_*` tables, authored in the Studio `design_*` workspace
and applied via the signed deployment envelope; both lanes reconcile the same
rows by business key. Per-tenant metadata customization is **not supported**.

### The 5 annotations

`@Model` / `@Field` / `@Index` live in `io.softa.framework.orm.annotation`;
`@OptionSet` / `@OptionItem` live in `io.softa.framework.base.annotation`
(together with the `OptionItemTone` / `OptionItemIcon` enums in
`io.softa.framework.base.enums`) so that framework-level enums in
`softa-base` — e.g. `Language` — can carry them without a module cycle.

```java
// Reference form — every attribute shown. In real code OMIT the ones marked
// "default" (see "Omit redundant attributes" below): the idiomatic Customer
// needs only @Model(businessKey = {"code"}) here.
@Model(
    label = "Customer",
    tableName = "customer",                     // default: snake_case(modelName)
    businessKey = {"code"},
    idStrategy = IdStrategy.DB_AUTO_ID,         // default DB_AUTO_ID
    multiTenant = false,                        // default false
    storageType = StorageType.RDBMS,
    softDelete = false, activeControl = false, timeline = false,
    versionLock = false,
    copyable = true                             // default true; false = copy APIs reject + UI hides Duplicate (runtime/log models)
)
@Index(fields = {"status", "createdTime"})            // @Repeatable; index names are GLOBALLY unique (≤60 chars, boot-enforced)
@Index(indexName = "uk_customer_email", fields = {"email"}, unique = true,
       message = "This email is already registered.")  // message: unique-only, shown on violation (its own i18n key)
public class Customer extends AuditableModel {

    @Field(label = "Customer Name", required = true, length = 100)
    private String name;

    @Field private CustomerTier tier;           // enum → OPTION + optionSetCode auto-derived
}

@OptionSet(label = "Customer Tier")
public enum CustomerTier {
    @OptionItem(label = "VIP Gold") GOLD("g"),   // explicit: "VIP Gold" ≠ humanize("GOLD")
    SILVER("s");                                 // bare: label defaults to humanize("SILVER") = "Silver"

    @JsonValue private final String code;       // itemCode = @JsonValue
    CustomerTier(String code) { this.code = code; }
}
```

**Key inference rules**:
- `fieldType` inferred from Java type (`String→STRING`, `Integer→INTEGER`,
  `enum→OPTION`, `List<enum>→MULTI_OPTION`, `@Model POJO→MANY_TO_ONE`, etc.)
- `OPTION` / `MULTI_OPTION` **cannot be written explicitly** — only inferred
  from `enum` / `List<enum>` Java types
- **Code-as-id for rich reference masters**: a TO_ONE
  relation (`MANY_TO_ONE` / `ONE_TO_ONE`) joins on the related model's **id only** —
  `relatedField` is always `id` (leave it unset; a non-id value is **rejected at boot**).
  To store a portable business code in the FK, make the related master **code-as-id**:
  `@Model(idStrategy = EXTERNAL_ID)` with a `String id` that **is** the primary code (e.g.
  `Currency` id = ISO 4217 alpha-3, `CountryRegion` id = ISO 3166-1 alpha-2). The FK then
  names only the related model and stores the code (= the related id), e.g. `@Field(fieldType =
  FieldType.MANY_TO_ONE, relatedModel = Currency.class) private String defaultCurrency;` — no
  `relatedField`, no `length` (width mirrors the referenced id). The FK column physically
  **mirrors the referenced model's id** (a `String` code-as-id id → `VARCHAR(n)`, a `Long`
  surrogate → `BIGINT`); the resolved physical type is **materialized** into a system-computed
  `relatedFieldType` column (+ mirrored `length`/`scale`) at reconciliation time (annotation
  lane: boot scanner via `ReferenceColumnResolver`; studio lane: `DesignFieldController`).
  `relatedFieldType` is never declared on `@Field` — it's inferred. Flat enums stay
  `@OptionSet` (e.g. `Continent`); only attribute/relation-rich masters use code-as-id.
- `columnName` defaults to `snake_case(fieldName)`; `tableName` defaults to
  `snake_case(modelName)`; index name defaults to `idx_<table>_<col>...` /
  `uk_<table>_<col>...` for unique
- `length` / `scale` default to the **type-default**, resolved at the metadata
  layer (`AnnotationParser` → `BuiltinDdlMetadataResolver.builtinDefaultFor`), so
  `sys_field.length` carries the real column width (not null): `STRING` / `OPTION`
  → 64, `MULTI_STRING` / `ORDERS` → 256, `MULTI_OPTION` → 255, `MULTI_FILE` → 1024,
  `FILTERS` → 512, `BIG_DECIMAL` → (32, 8), `DOUBLE` → (24, 2); a `String` PK →
  24. Other types take no length. Declare `length` / `scale` **only to override**
  the type-default (e.g. `length = 100`, `length = 20000` → TEXT on MySQL above
  16383). This is sound for the annotation lane: it always renders DDL via the
  builtin resolver; the studio (no-code) lane keeps per-flavor defaults.
- `id` is always emitted to `sys_field` as the PK; its type is inferred from the
  declared Java field (`Long` / `String`). **Convention: write an explicit
  `@Field(label = "ID")` on `id`** (consistent with "annotate every declared field");
  `@Field(fieldType=…)` on `id` is rejected (type follows the Java field). Audit
  (`AuditableModel`) and timeline (`TimelineModel`) fields carry `@Field` on the base
  class → inherited by every model (scanner walks the superclass chain), so subclasses
  don't repeat them.
- Entity classes do **not** use `@Schema` — `@Model` / `@Field` are the single metadata
  source and OpenAPI is generated from them (match `SysModel`).
- `onDelete` (relational FK only): app-level delete strategy on a `MANY_TO_ONE` /
  `ONE_TO_ONE` — `RESTRICT` (block) / `CASCADE` (delete referrers) / `SET_NULL` (null the FK,
  hard-delete only); unset = KEEP (default, do nothing). Declared on the **FK** (single source of
  truth), never on `ONE_TO_MANY` (a parent→children cascade is `CASCADE` on the child's back-ref FK).
  Enforced in `ModelServiceImpl.deleteByIds`; no physical DB FK. Boot-rejected: `onDelete` on a
  non-TO_ONE field, `SET_NULL` on a `required` FK, a **cyclic / self-referential
  `CASCADE`** (delete such hierarchies in app code), a **`CASCADE` chain deeper than `MAX_CASCADE_DEPTH`
  models** (bounds recursion; the error names the full chain), a **`CASCADE` from a soft-delete parent to a
  hard-delete child** (a recoverable parent must not irreversibly delete children — make the child
  soft-delete too, or use RESTRICT/SET_NULL), or a **`CASCADE` from a shared (non-multi-tenant) parent
  to a multi-tenant child** (one delete would cascade across all tenants — use RESTRICT). A **timeline**
  target is allowed: the strategy fires on **entity deletion** (`deleteByIds` = all slices of the logical
  id); slice-level `deleteBySliceId` keeps the entity alive and does not trigger it. Runtime guard:
  a `CASCADE`/`SET_NULL` affecting more than `MAX_BATCH_SIZE` referrers per level is rejected (accidental
  high-fanout), and large deletes are chunked to `DEFAULT_BATCH_SIZE` to bound statement size.

**Omit redundant attributes** (an explicit value that equals what the parser
would derive is noise — a present attribute should signal a real override): omit
`label` when it equals `humanize(name)`; omit a boolean/enum attribute equal to
its annotation default (`required = false`, `copyable = true`, `multiTenant =
false`, `idStrategy = DB_AUTO_ID`, `@Index(unique = false)`, …); omit
`tableName` / `columnName` equal to `snake_case(name)`; omit `length` / `scale`
equal to the **type-default** above (so a bare `@Field private String x;` is a
VARCHAR(64) column). Omit `@OptionItem` entirely when its only effect would be
`label == humanize(constant)`. The rule is safe precisely because the parser
regenerates the identical `sys_*` value on omission. Keep the attribute only
when the value genuinely differs from the default (acronym labels like `ID` /
`JSON` that `humanize` would flatten, `length = 100`, relation `fieldType`s,
etc.).

### `scanner-scope` (which packages the scanner manages)

```yaml
# application-dev.yml — package regex list (NEVER non-empty in production)
system:
  metadata:
    scanner-scope:
      - "*"          # manage every package
```

`scanner-scope` is a list of regex patterns full-matched against each
`@Model`/`@OptionSet` class's **package name**. Dots are regex
metacharacters — write `io\.acme\.foo.*` for
sub-packages, `io\.acme\.foo` for that package only; a sole entry `"*"` means
"all".

| `scanner-scope` | Scanner runs | DDL execution | Drift detection |
|---|---|---|---|
| `["*"]` | Boot-time, eager, **all** packages | Auto: `CREATE TABLE` / `ADD COLUMN` / `MODIFY COLUMN` / `ADD INDEX`. **Never auto-DROP** — DROP COLUMN / DROP TABLE / DROP INDEX log WARN with copy-paste SQL. `tableName` changes are also warn-only (`RENAME TABLE` hint) | n/a |
| `["io\\.acme\\.foo.*", …]` | Boot-time, **in-scope packages only** | Same auto-policy, in-scope models only | n/a |
| empty / unset (prod default) | n/a | n/a | `MetadataAnnotationChecker` runs post-boot async, logs WARN if code-vs-DB drift detected |

Discovery is separate from management: `system.metadata.scan-base-packages`
(default `["io.softa"]`) names the classpath roots under which `@Model` /
`@OptionSet` classes are **discovered**, in addition to the app's own
`AutoConfigurationPackages`. The default makes framework / starter models
(system models, reference data, framework enums) discoverable out of the box;
`scanner-scope` still decides what gets reconciled.

Boot ordering and recovery: the scanner executes DDL **before** committing the
`sys_*` rows — a failed DDL leaves the rows unwritten, so the next boot
recomputes the same diff and retries (re-applied DDL degrades to WARN on
"already exists").

A **narrow scope on a shared dev database** lets each developer reconcile only
their own packages without clobbering others' rows —
out-of-scope rows are never read, written, or deleted. Caveats (not solved by
scoping): scope is per-package not per-class, the baseline is the shared live
`sys_*`, and physical-table collisions remain.

**Renames: declare the `renamedFrom` attribute** (the earlier
standalone `@RenamedFrom` annotation is retired). The scanner's set-based diff is
keyed by `fieldName` / `modelName` / `itemCode`, so an *undeclared* rename still
looks like "drop old + add new" → auto-adds the new column, warn-only on dropping
the old = **silent data divorce**. Declaring `@Field(renamedFrom = "oldName")` /
`@Model(renamedFrom = "OldName")` (a **single** `String` — the immediately-prior
name; **single-step, no chain**) makes the `DiffEngine` pair the two sides into a
single `Modification(kind=RENAME)`, which auto-executes `CHANGE COLUMN` (field) /
`ALTER TABLE … RENAME TO` (model) and updates the `sys_*` row in place (id
preserved) — data is carried, not divorced. A model rename cascades onto its
fields/indexes (`modelName`), so it shows no field churn. The same attribute is a
persisted column (`sys_*.renamed_from` / `design_*.renamed_from`), so the studio
lane reuses one mechanism. **Multi-version** lineage is not accumulated in the
attribute (single-step): a skipped version is handled by a manual migration
(annotation lane) or the snapshot sequence (studio). Guards: declaring a prior name
that is still a live field/model, or two siblings claiming one prior name, fail at
parse; "both the new and prior name already exist" fails fast (resolve the
half-applied rename manually). The manual `CHANGE COLUMN` migration in
[`annotation-lane.md`](docs/ai/framework/annotation-lane.md)'s
*Manual migrations* section is now only needed for renames you cannot express as a single-step
`renamedFrom` (e.g. an itemCode rename carrying business-data UPDATEs, or a
skipped-version chain).

**When is manual DDL/DML needed at all?** Additive structure (new
model/field/index/option, attribute widening — including `sys_*`'s own
schema) is annotation-only and self-applies; hand-written SQL is reserved
for data transformation, destructive cleanup, and business-data DML. Full
decision tree + authoring obligations:
[`deploy/migrations/README.md`](deploy/migrations/README.md).

### Metadata identity (`app_code`)

There is **no `ownership` tier column** on the `sys_*` / `design_*` catalog.
The annotation lane and the Studio no-code lane reconcile the **same rows,
matched by business key** (`modelName` / `fieldName` / `optionSetCode` /
`itemCode` + `renamedFrom`) — a same-key row is updated in place, never
duplicated per channel. The `Ownership` enum (`io.softa.framework.orm.enums`)
is retained but **unused**, reserved for future business-data scenarios;
nothing reads or writes it. Per-tenant metadata is out of scope — revisiting
it requires a new ADR plus a tenant dimension on the `sys_*` unique keys.

**App identity**: every runtime declares `system.app-code` in
`application.yml` (mandatory when metadata-starter is active; fail-fast at
boot). The swept `sys_*` tables carry `app_code` (replacing the old numeric
`app_id`, migration V8) — stamped **server-side** on every write path (scanner,
envelope, plan/apply); wire values are never trusted. Every signed studio call
(envelope, export) carries the target `appCode` and the runtime rejects
mismatches — the signature proves *who* is calling, the app-code handshake
proves the call was addressed to *this app*. `GET /upgrade/runtime/runtimeInfo`
returns `{appCode, buildVersion, databaseType}` for env binding verification
and plan/apply version fingerprints. Studio keeps numeric `appId` internally;
`DesignApp.appCode` is the cross-system join key.

### AI agent prompt templates

`docs/ai/` — copy-paste guidance for AI agents (Claude, Cursor, Copilot),
split by audience:

- **Downstream apps** (SDK consumers) → [`docs/ai/authoring/`](docs/ai/authoring/) — self-contained guides: entities, controllers/services, queries, placeholders, config, seed data.
- **Framework contributors** (this repo) → [`docs/ai/framework/`](docs/ai/framework/) — the internal overlay on top of `authoring/`: [`annotation-lane.md`](docs/ai/framework/annotation-lane.md) (scanner / `sys_*` reconciliation, DDL auto-execute policy, in-repo verification recipe, manual migrations).
- **Studio no-code lane** → [`docs/ai/studio-no-code.md`](docs/ai/studio-no-code.md) — no-code definitions via Studio Open API.

Verification recipe for annotation changes:
1. `mvn -B -ntp -DskipTests compile -pl <affected module> -am`
2. `mvn -B -ntp test -pl starters/metadata-starter -Dtest='AnnotationParserTest,SystemModelAnnotationTest,ReferenceDataAnnotationTest,TypeInferenceTest'`
3. `mvn -B -ntp test -pl <affected module>`
4. `git diff -- '<affected files>'` (and `git diff main...HEAD` for full PR scope)
5. (If a `scanner-scope: ["*"]` app is available) restart and watch logs:
   `MetadataAnnotationScanner: applied N row change(s) to sys_*`
   `DdlOrchestrator: CREATE TABLE ... OK` / `ALTER TABLE ... OK`

There is **no** `module-diff` / `metadata-test gen` / `field-add` cli — those
were planned but removed. AI agents use `Read` / `Edit` / `Write` directly
+ the `mvn` + `git` verification chain.

### Key reference documents

- [starters/metadata-starter/README.md](starters/metadata-starter/README.md) — annotation API, scanner-scope behavior matrix, `renamedFrom`, DDL auto-execute policy
- [framework/softa-orm/README.md](framework/softa-orm/README.md) — full `@Model` / `@Field` / `@Index` property reference
- [starters/studio-starter/README.md](starters/studio-starter/README.md) — metadata control plane: publish / merge / drift / import, connectors, `DesignAggregate`

## Coding Conventions

### Framework conventions (convention over configuration)

- Extend framework base classes: `AuditableModel` (entity), `EntityController` (REST), `EntityService` (CRUD). `EntityService` delegates to `ModelService`, which applies metadata-defined defaults, validation, and lifecycle hooks.
- Place service implementations under `service/impl` and annotate with `@Service`.
- Business model metadata is managed via `ModelManager`.

### Spring Boot

- Use standard component annotations (`@RestController`, `@Service`, `@Repository`, `@Component`).
- Prefer constructor injection over field injection.
- Configure via `application.yml`.

### Query / persistence

- Prefer `Filters` + `FlexQuery` + `EntityService` query methods for reads — they honor metadata-driven query rules.
- Prefer `EntityService` CRUD methods over direct DB access; they apply defaults, validation, and lifecycle hooks defined in metadata.
- `File` / `MultiFile` field types return `FileInfo` / `List<FileInfo>` with OSS download URLs.

### Extension

- Extend by implementing framework interfaces or overriding base-class methods before reaching for custom JDBC.
- Complex or non-standard business logic goes in the Service layer.

### Code standards

- Logging: `@Slf4j`.
- Enums: add `@JsonValue` for stable serialization.
- Default language: English (comments, log messages, exception messages).
- Dates: `LocalDate` / `LocalDateTime`.

### Design principles

DRY · SOLID · KISS · YAGNI · Separation of Concerns · Composition over Inheritance · Single Source of Truth · Loose Coupling, High Cohesion

## Technology Stack

| Concern | Library/Version                     |
|---|-------------------------------------|
| Framework | Spring Boot 4.1.0                        |
| Language | Java 25, Lombok 1.18.42             |
| ORM | Spring Data (JDBC)                  |
| Databases | MySQL 9.6.0, PostgreSQL 42.7.10     |
| Cache | Spring Data Redis                   |
| Search | Spring Data Elasticsearch           |
| Messaging | Apache Pulsar (Spring Boot Starter) |
| Object Storage | MinIO 9.0.0, Aliyun OSS 3.18.5      |
| ID Generation | CosID 3.0.5                         |
| Expression Engine | AviatorScript 5.4.3                 |
| Template Engine | Pebble                              |
| Parser Generation | ANTLR4 4.13.2                       |
| API Docs | SpringDoc OpenAPI 3.0.3             |
| Testing | JUnit 5, Mockito 5.20.0, JMH 1.37   |

## Infrastructure (Docker Compose)

`deploy/` contains Docker Compose files for local development:
- `deploy/demo-app/docker-compose.yml` — full demo-app stack
- Supporting stacks for Elasticsearch+Kibana, MinIO, Pulsar

## CI/CD

`.github/workflows/maven-deploy.yml` handles releases: resolves version from git tags, bumps versions, signs artifacts with GPG, and publishes to both GitHub Packages and Maven Central. Version bumping is also handled by `upgrade-versions.sh`.
