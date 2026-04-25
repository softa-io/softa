# Studio Starter

## Overview
Studio Starter provides a metadata design-time IDE that enables visual model design, code generation,
version control, and multi-environment deployment. It manages the full lifecycle from design to production
deployment for Softa metadata-driven applications.

Key capabilities:
- **Model Designer**: design Models, Fields, OptionSets, Views, Navigations, Validations, Onchange rules, Indexes, and generation mappings/templates
- **Code Generator**: generate template-driven code files from models via Pebble templates, preferring database-managed templates and field type mappings and falling back to classpath templates under `templates/code/`
- **DDL Generator**: generate DDL (CREATE TABLE, ALTER TABLE, DROP TABLE, indexes) from model definitions and merged change sets via Pebble templates, preferring database-managed SQL templates and DB type mappings
- **DDL Preview**: preview DDL SQL at every stage — WorkItem, Version, and Deployment — for easy copy-paste to a DB client
- **Version Control**: WorkItem-based change tracking with ES changelog integration, version sealing/unsealing, and freezing
- **Deployment**: direct Version-to-Env deployment with automatic released-version merging by `sealedTime`, DDL generation, and execution tracking; new environments merge all released versions up to the target
- **Multi-Environment Deployment**: deploy to Dev/Test/UAT/Prod environments over a signed remote channel
- **Drift Detection & Runtime Import**: compare the design-time snapshot with the live runtime per env, and optionally overwrite design-time metadata with the runtime state — covers both first-time seeding (design-time empty, runtime already populated) and drift repair after out-of-band SQL changes

## Template Engine

The Studio Starter uses [Pebble](https://pebbletemplates.io/) (v4.1.1) as its template engine for both
Java code generation and SQL DDL generation. Pebble uses `{{ var }}` / `{% if %}` syntax, which is
consistent with the project-wide `{{ }}` placeholder convention.

### Template Files
| Directory | Templates | Purpose |
| --- | --- | --- |
| `templates/code/` | `entity/{{modelName}}.java.peb`, `service/{{modelName}}Service.java.peb`, `service/impl/{{modelName}}ServiceImpl.java.peb`, `controller/{{modelName}}Controller.java.peb` | Fallback code generation templates. When no `DesignCodeTemplate` is configured, all `templates/code/**/*.peb` files are scanned; the relative directory becomes the default output subdirectory and the last path segment before `.peb` becomes the rendered output file name |
| `templates/sql/mysql/` | `CreateTable.peb`, `AlterTable.peb`, `DropTable.peb`, `AlterIndex.peb` | Fallback MySQL DDL templates |
| `templates/sql/postgresql/` | `CreateTable.peb`, `AlterTable.peb`, `DropTable.peb`, `AlterIndex.peb` | Fallback PostgreSQL DDL templates |

### Code Template Rules
- Database mode: `DesignCodeTemplate` entries are loaded by `codeLang` and sorted by `sequence`, then rendered into a `ModelCodeDTO.files` list.
- `DesignCodeTemplate.subDirectory` is a Pebble template. `null`, blank, whitespace, `.`, `./`, and `/` are treated as the zip root directory. Leading `./`, leading `/`, trailing `/`, duplicated `/`, and `\` are normalized.
- `DesignCodeTemplate.fileName` is a Pebble template and is used directly as the output file name. It must include the desired suffix itself, such as `{{modelName}}Service.java`; the current implementation does not auto-append `DesignCodeLang.fileExtension`.
- `DesignCodeTemplate.fileName` cannot be blank after rendering and cannot contain directory separators. Directory structure must be expressed only through `subDirectory`.
- Fallback mode is enabled only when no code template language is configured in the database. In that case, every classpath template under `templates/code/**/*.peb` is rendered.
- In fallback mode, the rendered output file name comes from the fallback template path itself. For example, `templates/code/service/{{modelName}}Service.java.peb` generates `service/SysModelService.java`.
- Generated single-file downloads use the rendered file name, while zip downloads use the rendered relative path. `downloadAllZip` prefixes each language package with `<codeLang>/`.

### Core Classes
| Class | Description |
| --- | --- |
| `TemplateEngine` | Pebble engine wrapper — singleton `PebbleEngine` with caching, no auto-escaping |
| `CodeGenerator` | Generates code files from `DesignModel`, preferring `DesignCodeTemplate` + `DesignFieldCodeMapping`; preview/download can target one language or package all configured languages |
| `DdlDialectRegistry` | Resolves the active Pebble DDL renderer by `DatabaseType` |
| `MySqlDdlDialect` / `PostgreSqlDdlDialect` | DDL generators that prefer `DesignSqlTemplate` + `DesignFieldDbMapping` and fall back to classpath SQL templates |
| `DesignGenerationMetadataResolver` | Central resolver for DB-managed templates/mappings/defaults with graceful fallback |
| `DdlContextBuilder` | Builds template-friendly DDL context objects from `DesignModel`, `DesignField`, and `DesignModelIndex` |
| `VersionDdl` / `VersionDdlImpl` | Converts `List<ModelChangesDTO>` to `DdlTemplateContext`, then renders combined DDL string (table + index) |

### Code Generation Output
- `ModelCodeDTO` groups the generated files of one model under one language.
- `ModelCodeDTO.files` is a list of `ModelCodeFileDTO`, rather than fixed `entity/service/controller` fields.
- `ModelCodeFileDTO` contains `templateId`, `templateName`, `sequence`, `subDirectory`, `fileName`, `relativePath`, and `content`.
- `downloadCode` locates a file by the rendered `relativePath` returned from `previewCode`.

### DDL Template Context

SQL templates do not read raw `DesignModel` / `DesignField` / `DesignModelIndex` entities directly.
Instead, `VersionDdlImpl` first converts merged row changes into template-friendly DTOs so template authors
only need to focus on SQL syntax instead of diff logic.

Top-level context:
- `DdlTemplateContext.createdModels`
- `DdlTemplateContext.deletedModels`
- `DdlTemplateContext.updatedModels`

Per-model context (`ModelDdlCtx`):
- Base metadata: `modelName`, `labelName`, `description`, `tableName`, `oldTableName`, `pkColumn`
- Table change flags: `renamed`, `tableCommentChanged`, `tableCommentText`
- Field groups: `createdFields`, `deletedFields`, `updatedFields`, `renamedFields`
- Index groups: `createdIndexes`, `deletedIndexes`, `updatedIndexes`, `renamedIndexes`
- Render flags: `hasTableChanges`, `hasFieldChanges`, `hasIndexChanges`, `hasAlterTableChanges`

Per-field context (`FieldDdlCtx`):
- Identity: `fieldName`, `columnName`, `oldColumnName`, `renamed`
- Display/comment: `labelName`, `description`, `commentText`
- Type/defaults: `fieldType`, `dbType`, `length`, `scale`, `required`, `autoIncrement`, `defaultValue`

Per-index context (`IndexDdlCtx`):
- Identity: `indexName`, `oldIndexName`, `renamed`
- Definition: `columns`, `unique`

Current MySQL template behavior:
- `CreateTable.peb` renders a single created model with `model.createdFields`
- `DropTable.peb` renders a single deleted model with `model.tableName`
- `AlterTable.peb` handles table rename, table comment change, and field `create/delete/update/rename`
- `AlterIndex.peb` handles index `create/delete/update/rename`
- String literals (descriptions, comments) are escaped via the `| sqlLiteral` Pebble filter to defend against single quotes
- Field rename is rendered as `DROP COLUMN oldColumnName` + `ADD COLUMN columnName ...`
- Index rename is rendered as `DROP INDEX oldIndexName` + `ADD INDEX indexName ...`
- Pure deleted indexes also generate SQL
- New-table indexes are sourced from `DesignModelIndex.createdRows`

This structure is intentionally aligned with metadata terminology (`Model`, `Field`, `Index`) so the same
Pebble SQL templates can be stored in the database and customized per application with lower cognitive load.

## Dependency
```xml
<dependency>
  <groupId>io.softa</groupId>
  <artifactId>studio-starter</artifactId>
  <version>${softa.version}</version>
</dependency>
```

## Requirements
- **metadata-starter**: provides runtime metadata model management and upgrade API.
- **es-starter**: provides Elasticsearch changelog storage for version control change tracking.
- Database contains the studio metadata tables (see Data Model below).

## Remote Deployment Configuration
- `DesignAppEnv.upgradeEndpoint` must point to the target runtime base URL. The studio appends `/metadata/upgrade` and `/metadata/exportRuntimeMetadata` automatically.
- The studio application must set `system.public-access-url`. Remote deployment derives the runtime callback URL as `<system.public-access-url>/DesignDeployment/callback`; without it, dispatch fails before the request is sent.
- Call `POST /DesignAppEnv/issueKey?id=` before the first remote deploy, then paste the returned public key into the paired runtime's `system.runtime-public-key`. The runtime only registers the metadata signature verification filter when that property is non-blank.
- Outbound studio -> runtime HTTP uses the Resilience4j client name `studio-remote`; runtime -> studio callback uses `metadata-callback`. If you do not define explicit YAML for those instances, the registry defaults still apply.

Minimal example:
```yaml
# studio
system:
  public-access-url: https://studio.example.com

resilience4j:
  retry:
    instances:
      studio-remote:
        max-attempts: 3
  circuitbreaker:
    instances:
      studio-remote:
        sliding-window-size: 20
```

```yaml
# target runtime
system:
  runtime-public-key: <paste-issued-public-key>
```

## Data Model

### Core Design Models
| Entity | Description                                                                                                                         |
| --- |-------------------------------------------------------------------------------------------------------------------------------------|
| `DesignPortfolio` | Portfolio / project grouping for apps                                                                                               |
| `DesignApp` | Application definition (name, code, databaseType, packageName). `packageName` is passed directly into the code template context     |
| `DesignModel` | Model definition (fields, indexes, tableName, storageType, etc.)                                                                    |
| `DesignField` | Field definition (fieldType, length, scale, relatedModel, etc.)                                                                     |
| `DesignFieldDbMapping` | Field type to database type mapping                                                                                                 |
| `DesignFieldTypeDefault` | Default metadata values for each field type                                                                                         |
| `DesignFieldCodeMapping` | Language-specific property type mapping for each field type                                                                         |
| `DesignSqlTemplate` | Database-managed Pebble SQL templates per database type                                                                             |
| `DesignCodeTemplate` | Database-managed Pebble code templates per language with configurable `sequence`, `subDirectory`, `fileName`, and `templateContent` |
| `DesignModelIndex` | Model index definition                                                                                                              |
| `DesignView` | View definition                                                                                                                     |
| `DesignNavigation` | Navigation definition                                                                                                               |
| `DesignOptionSet` | Option set definition                                                                                                               |
| `DesignOptionItem` | Option item definition                                                                                                              |

### Version Control & Deployment Models
| Entity | Description |
| --- | --- |
| `DesignAppEnv` | Application environment (Dev/Test/UAT/Prod), stores environment config and deployment cursor such as `currentVersionId`; it does not store the full metadata snapshot |
| `DesignAppEnvSnapshot` | Per-deployment snapshot of the full expected runtime metadata JSON. One row per deployment, uniquely keyed by `(appId, envId, deploymentId)` |
| `DesignAppEnvDrift` | Cached drift between the latest snapshot and the live runtime for an env, one row per `(appId, envId)` — refreshed after deployments and on demand |
| `DesignWorkItem` | Change work item — scopes a unit of design changes via ES correlationId; `versionId` links it to a Version and `closedTime` records when it is closed by deployment |
| `DesignAppVersion` | Version shell — aggregates WorkItem changes; `versionType` distinguishes `Normal` and `Hotfix`, and released ordering is determined by `status + sealedTime` |
| `DesignDeployment` | Immutable deployment record — merged content from the sealedTime release interval with DDL and execution results |
| `DesignDeploymentVersion` | Audit record linking a deployment to the versions it merged |

Environment snapshot relationship:
- `DesignAppEnv` is the environment state record. It owns deployment progress (`currentVersionId`) and upgrade configuration.
- `DesignAppEnvSnapshot` stores the full expected runtime metadata state. Each successful deployment writes its own row, uniquely keyed by `(appId, envId, deploymentId)` — the latest row (highest id) is the effective snapshot for drift comparison.
- After a deployment commits, the next snapshot is built asynchronously as `previous_snapshot + mergedChanges` and upserted, so the write is idempotent against event replay.
- `importFromRuntime` / `applyDrift` also write a snapshot row after overwriting design-time with runtime state. The synthetic `DesignAppVersion` id doubles as the `deploymentId` slot (globally unique via CosID), so these rows coexist with deployment snapshots without collision.

### Current Runtime Sync Coverage
The current version-control and deployment pipeline upgrades the following design-time models to runtime metadata:
- `DesignModel` -> `SysModel`
- `DesignModelTrans` -> `SysModelTrans`
- `DesignField` -> `SysField`
- `DesignFieldTrans` -> `SysFieldTrans`
- `DesignModelIndex` -> `SysModelIndex`
- `DesignOptionSet` -> `SysOptionSet`
- `DesignOptionSetTrans` -> `SysOptionSetTrans`
- `DesignOptionItem` -> `SysOptionItem`
- `DesignOptionItemTrans` -> `SysOptionItemTrans`
- `DesignView` -> `SysView`
- `DesignNavigation` -> `SysNavigation`

Runtime-only companion models are still outside the deployment stream. For example, `SysViewDefault` remains user-personal runtime state rather than design-time metadata.

Runtime export (`/metadata/exportRuntimeMetadata`) is app-scoped: the studio passes the env's `appId`, and the runtime filters by the `appId` column for main models or joins through the parent row for translation models (`*Trans`). A single runtime hosting multiple apps will never leak sibling-app rows into drift comparison or import.

## DDL Storage Design

**Version** stores only change data (`versionedContent` = `List<ModelChangesDTO>` JSON), **not** DDL.
DDL is always generated on-the-fly from change data via `VersionDdlImpl -> DdlTemplateContext -> Pebble templates`.
This design ensures:
- DDL reflects the latest template version (templates may be upgraded independently)
- No consistency maintenance burden between stored DDL and templates
- Near-zero computation cost for on-the-fly generation
- Version DDL is intermediate (Deployment merges multiple Versions before execution)

**Deployment** stores pre-rendered DDL strings (`mergedDdlTable`, `mergedDdlIndex`) as the final
deployment artifact. This is the DDL that is actually executed against the target database.
Deployment is a self-contained, immutable record that includes merged content, DDL, and execution results.

Recommended UI presentation for Deployment DDL:
- Use one `DDL` tab in the deployment detail page
- Render `mergedDdlTable` and `mergedDdlIndex` as two sections in the same tab, not as one flattened field
- Provide `Copy All`, `Copy Table`, and `Copy Index`
- `Copy All` should concatenate `mergedDdlTable + mergedDdlIndex`
- Hide a section if its content is empty

## Workflow

### Design-Time Workflow
1. Create a **Portfolio** and **App**
2. Design **Models**, **Fields**, **OptionSets**, **Views**, **Navigations**, etc.
3. Preview DDL and generated files for models

### Version Control Workflow
1. Create a **WorkItem** and mark it `IN_PROGRESS`
2. Make design changes (CRUD on models, fields, etc. — changes are logged to ES by WorkItem correlationId)
3. Complete the WorkItem → `doneWorkItem`
4. Create a **Version** (DRAFT) with `versionType = Normal | Hotfix` -> add completed WorkItems -> `sealVersion`
   - Sealing aggregates WorkItem changes, computes diffHash, transitions to SEALED
   - `unsealVersion` can revert a SEALED version back to DRAFT if it has not been deployed
5. Deploy a released version
6. After successful deployment, included `DONE` WorkItems are marked `CLOSED` and `closedTime` is recorded
7. `freezeVersion` after production deployment (immutable)

Notes:
- There is no `readyWorkItem` or `startWorkItem` endpoint in the current implementation.

### Deployment Workflow
1. **Incremental Deploy**: `POST /DesignAppVersion/deployToEnv`
   - Request body: `{ "versionId": ..., "envId": ... }`
   - Selects released versions by `sealedTime` in `(env.currentVersionId, targetVersion]`
   - Merges version contents via `VersionMerger`, generates DDL
   - Creates a self-contained Deployment record with merged content + DDL
   - Dispatches the signed upgrade envelope to the target runtime and advances `env.currentVersionId` once the runtime reports success
   - After the deployment transaction commits, asynchronously rebuilds or updates the env's `DesignAppEnvSnapshot`
   - Auto-freezes versions after successful PROD deployment
   - For new environments (no `currentVersionId`), all released versions up to the target are merged
2. **Retry**: `POST /DesignDeployment/retry?id=`
3. **Cancel stuck deployment**: `POST /DesignDeployment/cancel?id=`
   - Only valid for `PENDING` / `DEPLOYING`
   - Marks the record `ROLLED_BACK` and releases the env mutex
   - Does **not** undo runtime DDL or data changes that may already have been applied

Notes:
- Every deploy is async end-to-end — `deployToEnv` returns the deployment id once the record is persisted, and completion is reported later by the target runtime's webhook at `POST /DesignDeployment/callback`.
- `DesignDeploymentStatus` contains `ROLLED_BACK`, but there is currently no automatic rollback — `cancelDeployment` only marks the record rolled back and releases the env mutex so the next deployment can proceed.

## Key APIs

### Model Design
| Endpoint | Description |
| --- | --- |
| `GET /DesignModel/previewDDL?id=` | Preview CREATE TABLE DDL for a model |
| `GET /DesignModel/previewCode?id=&codeLang=` | Preview generated code files for one language, including rendered relative paths. `codeLang` is optional only when exactly one language package is available |
| `GET /DesignModel/previewAllCode?id=` | Preview all generated language packages for a model |
| `GET /DesignModel/downloadCode?id=&codeLang=&relativePath=` | Download one generated file by its rendered relative path. `relativePath` must come from `previewCode` |
| `GET /DesignModel/downloadZip?id=&codeLang=` | Download one language package as a ZIP. `codeLang` is optional only when exactly one language package is available |
| `GET /DesignModel/downloadAllZip?id=` | Download all generated language packages in one ZIP, grouped under `<codeLang>/` |

### WorkItem Lifecycle
| Endpoint | Description |
| --- | --- |
| `POST /DesignWorkItem/doneWorkItem?id=` | Complete — end change tracking |
| `GET /DesignWorkItem/previewChanges?id=` | Preview accumulated metadata changes |
| `GET /DesignWorkItem/previewDDL?id=` | Preview DDL SQL from WorkItem changes (copy to DB client) |
| `POST /DesignWorkItem/addToVersion` | Add a DONE WorkItem to a DRAFT Version. Body: `{ "workItemId": ..., "versionId": ... }` |
| `POST /DesignWorkItem/removeFromVersion?id=` | Remove a WorkItem from its current DRAFT Version |
| `POST /DesignWorkItem/cancelWorkItem?id=` | Cancel the work item |
| `POST /DesignWorkItem/deferWorkItem?id=` | Defer the work item |
| `POST /DesignWorkItem/reopenWorkItem?id=` | Reopen a completed/cancelled/deferred work item |

### Version Lifecycle
| Endpoint | Description |
| --- | --- |
| `POST /DesignAppVersion/createOne` | Create a new version (DRAFT, `versionType` = `Normal` or `Hotfix`, default `Normal`) |
| `POST /DesignAppVersion/deployToEnv` | Deploy a `SEALED` or `FROZEN` version to an environment. Body: `{ "versionId": ..., "envId": ... }` |
| `GET /DesignAppVersion/previewVersion?id=` | Preview merged version content |
| `GET /DesignAppVersion/previewDDL?id=` | Preview DDL SQL from version content (copy to DB client) |
| `POST /DesignAppVersion/sealVersion?id=` | Seal version (DRAFT → SEALED) |
| `POST /DesignAppVersion/unsealVersion?id=` | Unseal version (SEALED → DRAFT, if not deployed) |
| `POST /DesignAppVersion/freezeVersion?id=` | Freeze version (SEALED → FROZEN) |

### Deployment
| Endpoint | Description |
| --- | --- |
| `POST /DesignDeployment/retry?id=` | Retry a failed deployment by creating a new Deployment with the same parameters |
| `POST /DesignDeployment/cancel?id=` | Cancel a stuck deployment (PENDING/DEPLOYING) and release the env mutex. No automatic rollback — runtime changes already applied stay applied |
| `POST /DesignDeployment/callback` | Webhook endpoint — the runtime POSTs here with the SUCCESS/FAILURE payload once an async upgrade completes. Header `X-Softa-Callback-Token` must match the pending deployment |

### Environment
| Endpoint                                            | Description |
|-----------------------------------------------------| --- |
| `GET /DesignAppEnv/compareDesignWithRuntime?id=` | Read the cached drift between `DesignAppEnvSnapshot` and runtime metadata |
| `POST /DesignAppEnv/refreshDrift?id=`            | Kick off an async drift recompute; poll `compareDesignWithRuntime` for the result |
| `POST /DesignAppEnv/applyDrift?id=`              | Overwrite design-time metadata with the cached runtime drift (useCached=true — operator accepts the current drift report as the new truth) |
| `POST /DesignAppEnv/importFromRuntime?id=`       | Refresh drift against the live runtime, then overwrite design-time with the result (useCached=false — first-time seed from an already-populated runtime) |
| `POST /DesignAppEnv/issueKey?id=`                | Issue / rotate the Ed25519 keypair used to sign studio → runtime requests. Returns the new public key — the operator pastes it into the paired runtime's `system.runtime-public-key`. Each runtime pairs with exactly one env, so rotation is an atomic yml swap rather than a multi-key grace period |

Both `applyDrift` and `importFromRuntime` share one service method with a `useCached` boolean. They acquire the env's deployment mutex (concurrent with deploy — one at a time per env), mint a FROZEN `DesignAppVersion` named `imported-from-runtime-<ISO>`, advance `env.currentVersionId` to it, write a post-import snapshot keyed by the synthetic version id, and clear the drift cache. No-op when drift is empty.

### App And Portfolio Status
| Endpoint | Description |
| --- | --- |
| `POST /DesignApp/activate?id=` | Activate an App |
| `POST /DesignApp/enterMaintenance?id=` | Put an App into maintenance mode |
| `POST /DesignApp/deprecate?id=` | Deprecate an App |
| `POST /DesignPortfolio/activate?id=` | Activate a Portfolio |
| `POST /DesignPortfolio/archive?id=` | Archive a Portfolio |

## Row-Level Merge (model + rowId)
Changes are tracked and merged at the **model + rowId** level throughout the entire pipeline:

### WorkItem → Version (seal)
`VersionControlImpl` queries ES changelogs by `correlationId IN (workItemIds)`, groups by `rowId`,
and merges multiple changelog entries for the same row into a single `RowChangeDTO`.
A row that is both created and deleted within the same WorkItem set is cancelled (no net change).

### Version → Deployment (deploy)
`VersionMerger` merges multiple Version contents using a `modelName → (rowId → RowChangeDTO)` map.
When the same row is modified across multiple Versions, the state machine folds the changes:

| V1 Action | V2 Action | Net Result |
| --- | --- | --- |
| CREATE | UPDATE | CREATE (with V2 data) |
| CREATE | DELETE | Cancelled (no net change) |
| UPDATE | UPDATE | UPDATE (merged before/after) |
| UPDATE | DELETE | DELETE (V1 dataBeforeChange) |
| DELETE | CREATE | UPDATE (re-creation) |

This ensures a single record modified multiple times across WorkItems and Versions is published
as **one net change** in the Deployment — no duplicate or redundant operations.

Deployment selects the versions to merge from the app's released stream:
- only `SEALED` and `FROZEN` versions participate
- versions are ordered by `sealedTime ASC`
- the merge interval is `(env.currentVersionId, targetVersion]`
- `targetVersion` must itself be `SEALED` or `FROZEN`

## Current Gaps

### Highest-Priority Fixes
1. **Close the deployment contract gaps**
   - `DesignDeploymentStatus` includes `ROLLED_BACK`, but no automatic rollback service exists — `cancelDeployment` only unlocks the env for the next attempt.
2. **Clean up reserved or partially wired environment/config fields**
   - `DesignAppEnv.protectedEnv`, `active`, and `autoUpgrade` are not currently enforced in deployment flow.

### Additional Notes
- `DesignView.defaultView` is currently only a design-time flag. Runtime personal default views are managed by `SysViewDefault`.
- `DesignOptionSet.optionItems` exists on the entity, but the current starter relies on separate `DesignOptionItem` records rather than automatically hydrating this collection.
- Some metadata fields are consumed outside `studio-starter`, mainly by `softa-orm` and `metadata-starter`. For example, `displayName`, `searchName`, `activeControl`, `multiTenant`, `versionLock`, `relatedField`, `joinLeft`, `joinRight`, `cascadedField`, `translatable`, `encrypted`, and `maskingType` do take effect at runtime.

## Status Enums

### DesignAppStatus
`Active` / `Maintenance` / `Deprecated`
- `Active → Maintenance`: `enterMaintenance`
- `Active → Deprecated`: `deprecate`
- `Maintenance → Active`: `activate`

### DesignPortfolioStatus
`Active` / `Archived`
- `Active → Archived`: `archive`
- `Archived → Active`: `activate`

### DesignAppEnvStatus
`Stable` / `Deploying`
- Per-environment deployment mutex via conditional update (compare-and-set on `envStatus`)
- `Stable → Deploying`: acquired when a deployment starts
- `Deploying → Stable`: released when deployment finishes (success, failure, or cancel)

### DesignAppVersionStatus
`DRAFT` ⇄ `SEALED` → `FROZEN`
- `DRAFT → SEALED`: `sealVersion` — aggregates WorkItem changes, computes diffHash
- `SEALED → DRAFT`: `unsealVersion` — only if not deployed (no Deployment reference)
- `SEALED → FROZEN`: `freezeVersion` — after production deployment, immutable

### DesignAppVersionType
`Normal` / `Hotfix`
- `Normal`: planned release version
- `Hotfix`: emergency patch release version

### DesignWorkItemStatus
`IN_PROGRESS` → `DONE` → `CLOSED`
- `IN_PROGRESS -> DONE`: `doneWorkItem`
- `IN_PROGRESS / DONE / DEFERRED -> CANCELLED`: `cancelWorkItem`
- `IN_PROGRESS -> DEFERRED`: `deferWorkItem`
- `DONE / CANCELLED / DEFERRED -> IN_PROGRESS`: `reopenWorkItem`
- `DONE -> CLOSED`: set automatically when a deployment finishes successfully

### DesignDeploymentStatus
`PENDING` → `DEPLOYING` → `SUCCESS` / `FAILURE` / `ROLLED_BACK`

### DesignDriftCheckStatus
`Success` / `Failure`
- `Success`: drift check completed — `driftContent` reflects the actual runtime state
- `Failure`: drift check failed (e.g. remote env unreachable) — previous drift content is kept as-is

### DesignAppEnvType
`DEV`, `TEST`, `UAT`, `PROD`
