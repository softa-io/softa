# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

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
│   ├── ai-starter               # LLM chat via Spring AI (OpenAI/Azure/DeepSeek/Anthropic)
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
auto-applying the corresponding DDL changes. Runtime `sys_*` rows are scoped by
`app_code` (`system.app-code`); the earlier `ownership` column has been retired
from the runtime catalog. Studio/connector publish writes the same app-scoped
catalog for production, and per-tenant runtime metadata customization is not
represented as separate `sys_*` rows.

### The 5 annotations (`io.softa.framework.orm.annotation`)

```java
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
- `columnName` defaults to `snake_case(fieldName)`; `tableName` defaults to
  `snake_case(modelName)`; index name defaults to `idx_<table>_<col>...` /
  `uk_<table>_<col>...` for unique
- explicit `tableName`, `columnName`, and `indexName` must satisfy
  `StringTools.isTableOrColumn` and must not be SQL reserved words; DDL renders
  identifiers unquoted
- `id` is always emitted to `sys_field` as the PK; its type is inferred from the
  declared Java field (`Long` / `String`). **Convention: write an explicit
  `@Field(label = "ID")` on `id`** (consistent with "annotate every declared field");
  `@Field(fieldType=…)` on `id` is rejected (type follows the Java field). Audit
  (`AuditableModel`) and timeline (`TimelineModel`) fields carry `@Field` on the base
  class → inherited by every model (scanner walks the superclass chain), so subclasses
  don't repeat them.
- `onDelete` (relational FK only): app-level delete strategy on `MANY_TO_ONE` /
  `ONE_TO_ONE` — `RESTRICT` / `CASCADE` / `SET_NULL`; unset = KEEP (default). Declared on the FK,
  never on `ONE_TO_MANY`. No physical DB FK; rejected at boot: cyclic / self-referential `CASCADE`, a
  `CASCADE` chain deeper than `MAX_CASCADE_DEPTH`, a `CASCADE` from a soft-delete parent to a hard-delete
  child, and a `CASCADE` from a shared parent to a multi-tenant child. Runtime: a `CASCADE`/`SET_NULL`
  over `MAX_BATCH_SIZE` referrers per level is rejected.

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
| `["*"]` | Boot-time, eager, **all** packages | Auto: `CREATE TABLE` / `ADD COLUMN` / `MODIFY COLUMN` / `ADD INDEX`. **Never auto-DROP** — DROP COLUMN / DROP TABLE / DROP INDEX log WARN with copy-paste SQL | n/a |
| `["io\\.acme\\.foo.*", …]` | Boot-time, **in-scope packages only** | Same auto-policy, in-scope models only | n/a |
| empty / unset (prod default) | n/a | n/a | `MetadataAnnotationChecker` runs post-boot async, logs WARN if code-vs-DB drift detected |

A **narrow scope on a shared dev database** lets each developer reconcile only
their own packages. Caveats: scope is per-package not per-class, it is not an
ownership barrier, the baseline is the shared live `sys_*`, app identity is
still `app_code`, and physical-table collisions remain.

**Renames: declare the `renamedFrom` attribute** (the earlier
standalone `@RenamedFrom` annotation is retired). The scanner's set-based diff is
keyed by `fieldName` / `modelName` / `itemCode`, so an *undeclared* rename still
looks like "drop old + add new" → auto-adds the new column, warn-only on dropping
the old = **silent data divorce**. Declaring `@Field(renamedFrom = "oldName")` /
`@Model(renamedFrom = "OldName")` (a **single** `String` — the immediately-prior
name; **single-step, no chain**) makes the `DiffEngine` pair the two sides into a
single `Modification(kind=RENAME)`, which auto-executes `CHANGE COLUMN` (field) /
`ALTER TABLE … RENAME TO` (model) and updates the `sys_*` row in place (id
preserved) — data is carried, not divorced. A skipped-version chain or an
`@OptionItem` `@JsonValue` code rename still needs a hand-written migration. See the *Manual migrations* section of
[`annotation-lane.md`](docs/ai/framework/annotation-lane.md).

**When is manual DDL/DML needed at all?** Additive structure (new
model/field/index/option, attribute widening — including `sys_*`'s own
schema) is annotation-only and self-applies; hand-written SQL is reserved
for data transformation, destructive cleanup, Studio/connector-managed rows,
and business-data DML. Full decision tree + authoring obligations:
[`deploy/migrations/README.md`](deploy/migrations/README.md).

### Runtime catalog identity (`app_code`)

Rows in `sys_model` / `sys_field` / `sys_option_set` / `sys_option_item` /
`sys_model_index` are app-scoped by `app_code`. The scanner stamps rows with
the configured `system.app-code`; runtime export/apply APIs require the same
app code as a handshake. The retired `Ownership` enum remains in code only as a
reserved business-data concept; there is no `ownership` column on current
runtime `sys_*` tables.

Softa supports one app using multiple databases and multiple apps sharing one
database. The invariant is that each app has a stable, distinct `app_code`; FK
backfill and runtime metadata operations match parent/child rows by `app_code`
so shared databases do not cross-link catalogs.

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

| Concern | Library/Version            |
|---|----------------------------|
| Framework | Spring Boot 4.1.0               |
| Language | Java 25, Lombok 1.18.42    |
| ORM | Spring Data (JDBC)         |
| Databases | MySQL 9.6.0, PostgreSQL 42.7.10 |
| Cache | Spring Data Redis          |
| Search | Spring Data Elasticsearch  |
| Messaging | Apache Pulsar (Spring Boot Starter) |
| Object Storage | MinIO 9.0.0, Aliyun OSS 3.18.5 |
| ID Generation | CosID 3.0.5                |
| Expression Engine | AviatorScript 5.4.3        |
| Template Engine | Pebble                |
| Parser Generation | ANTLR4 4.13.2              |
| API Docs | SpringDoc OpenAPI 3.0.3    |
| Testing | JUnit 5, Mockito 5.20.0, JMH 1.37 |

## Infrastructure (Docker Compose)

`deploy/` contains Docker Compose files for local development:
- `deploy/demo-app/docker-compose.yml` — full demo-app stack
- Supporting stacks for Elasticsearch+Kibana, MinIO, Pulsar

## CI/CD

`.github/workflows/maven-deploy.yml` handles releases: resolves version from git tags, bumps versions, signs artifacts with GPG, and publishes to both GitHub Packages and Maven Central. Version bumping is also handled by `upgrade-versions.sh`.
