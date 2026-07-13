# Studio Starter

## Overview

Studio Starter is the metadata control plane for Softa: the design-time IDE plus
the cross-environment governance engine. It lets an operator design
per-environment metadata, preview generated DDL, publish the desired metadata
state to a target runtime, import or reverse-engineer runtime state back into
Studio, merge designs between environments, and restore a prior activity
snapshot. It does not generate business code — the runtime is
annotation/scanner-driven and needs no generated per-entity code.

Current implementation shape:

- Each `DesignAppEnv` owns a full env-scoped design set. The env's live
  `design_*` rows are the desired state; there is no WorkItem, Version, or
  Deployment model in the current code.
- `DesignActivity` is the audit record for operations such as publish, import,
  reverse, and merge. Every succeeded activity captures a `DesignSnapshot` of
  the post-operation design for later restore.
- Publishing is desired-state based: Studio diffs env design rows against the
  target connector, renders DDL for structural changes, projects row changes to
  `MetadataChangeSet`, and lets the connector apply the result.
- Connector targets are `SOFTA` (signed runtime upgrade API) and `JDBC` (raw
  database DDL execution and physical schema reverse).

## Template Engine

Studio uses Pebble (`{{ var }}` / `{% if %}`) for SQL DDL templates resolved
through the DDL dialect layer. There is no business-code generator: the runtime
is annotation/scanner-driven and needs no generated per-entity code, and
business-code authoring is expected to be AI-assisted from the metadata rather
than rendered from fixed templates.

### DDL Rendering

DDL rendering is shared with metadata-starter's annotation DDL infrastructure.
The catalog stores only the **logical** type (`fieldType` + `length`/`scale`);
the physical column type is never stored — it is a connector projection,
resolved per target dialect at render time.

- `MetadataChangeDdlRenderer` converts row-level metadata changes to
  `DdlTemplateContext`.
- `ConnectorFactory` selects the `DdlDialect` for the target env through
  `DdlDialectFactory`.
- `SOFTA` connectors use the builtin resolver, matching the boot-time annotation
  scanner.
- `JDBC` connectors adapt `DesignDdlTemplateResolver` through
  `DesignDdlMetadataResolver`, so `DesignFieldDbMapping` and `DesignSqlTemplate`
  can customize external database DDL without becoming global DDL beans.
- Classpath fallback SQL templates are in metadata-starter, not in
  studio-starter.

Rendered DDL is split into per-statement payloads by `DdlSqlSplitter`, which
delegates statement-boundary parsing to metadata-starter's `SqlStatements` lexer
so semicolons inside comments and quoted SQL literals are safe.

For newly created models, index creation should have a single owner in the
dialect's create-model rendering path. Different databases can implement that
owner differently: MySQL can inline indexes in `CREATE TABLE`, while PostgreSQL
emits separate `CREATE INDEX` statements from the create template.

## Current Runtime Sync Coverage

The desired-state publish, drift, import, and env-to-env merge path currently
sweeps these env-scoped design models:

| Design model | Runtime model |
| --- | --- |
| `DesignModel` | `SysModel` |
| `DesignField` | `SysField` |
| `DesignModelIndex` | `SysModelIndex` |
| `DesignOptionSet` | `SysOptionSet` |
| `DesignOptionItem` | `SysOptionItem` |

The swept tables' topology — design entity ↔ `MetaTable`, business-key attrs,
parent/FK links, rename-bridge column, checksum attrs, and FK-safe apply/delete
order — is single-sourced in the `DesignAggregate` descriptor enum
(`release/dto`); the differ, merger, importer, cloner, env-delete cascade, and
DTO grouping all derive from it.

The following design models exist, but are not part of the current
desired-state sweep: `DesignModelTrans`, `DesignFieldTrans`,
`DesignOptionSetTrans`, `DesignOptionItemTrans`, `DesignView`, and
`DesignNavigation`. Treat that as an explicit implementation gap until those
models are added to the `DesignAggregate` descriptor plus the pieces a new
table inherently needs: `DesignRows`, `MetaTable`, connector read/apply paths,
checksums, and tests (merge/import derive from the descriptor).

## Dependencies

```xml
<dependency>
  <groupId>io.softa</groupId>
  <artifactId>studio-starter</artifactId>
  <version>${softa.version}</version>
</dependency>
```

Runtime module dependencies:

- `metadata-starter`: runtime metadata entities, DDL dialects, checksums, and
  upgrade DTOs. This is the module's only Softa starter dependency.

## Environment Configuration

`DesignAppEnv` selects how Studio talks to a target:

- `connectorType = SOFTA` targets a Softa runtime through signed HTTP upgrade
  APIs. `upgradeEndpoint`, `databaseType`, and an issued keypair are required.
- `connectorType = JDBC` targets a raw JDBC database. `jdbcUrl`, credentials,
  and `databaseType` are required. Apply is DDL-only because a raw database has
  no `sys_*` metadata rows.
- `autoExecuteDDL` is honored by the SOFTA connector. When false, Studio still
  publishes metadata row changes but ships no DDL; a DBA runs the recorded DDL
  out of band.

Key setup for a SOFTA env:

1. Call `POST /DesignAppEnv/issueKey?id=<envId>`.
2. Put the returned public key into the target runtime's
   `system.metadata.public-key`.
3. Keep Studio's generated private key only in `DesignAppEnv.privateKey` —
   stored ORM-encrypted at rest, never returned by search, and not carried by
   `copyById`.

## Core Data Model

### Design Metadata

| Entity | Purpose |
| --- | --- |
| `DesignModel` | Model/table definition, app/env scope, business key, storage flags |
| `DesignField` | Field/column definition and relation metadata |
| `DesignModelIndex` | Model index definition |
| `DesignOptionSet` | Option-set root |
| `DesignOptionItem` | Option-set item |
| `DesignView` | Design-time view definition, not swept by publish yet |
| `DesignNavigation` | Design-time navigation definition, not swept by publish yet |
| `Design*Trans` | Design-time translation rows, not swept by publish yet |

### DDL Template Metadata

| Entity | Purpose |
| --- | --- |
| `DesignFieldDbMapping` | Field type to database type mapping for design-backed DDL dialects |
| `DesignSqlTemplate` | Database-managed SQL template override per database type |
| `DesignFieldDomain` | Reusable one-time field template applied through `DesignField.applyDomain` |

### Release and Audit

| Entity | Purpose |
| --- | --- |
| `DesignApp` | Application identity, code, package name, and lifecycle status |
| `DesignAppEnv` | Target environment and connector configuration; owns env-scoped design |
| `DesignActivity` | Audit record for publish/import/reverse/merge/cancel/restore related work |
| `DesignSnapshot` | Full env design snapshot captured after successful activities |

## Main Workflows

### Design and Preview

1. Create a `DesignApp`.
2. Create one or more `DesignAppEnv` rows.
3. Seed a new env from an existing env, import from a Softa runtime, or reverse
   a JDBC schema if needed.
4. Edit env-scoped `DesignModel`, `DesignField`, `DesignModelIndex`,
   `DesignOptionSet`, and `DesignOptionItem` rows.
5. Use `GET /DesignModel/previewDDL` to preview the generated DDL.

### Publish

1. `POST /DesignAppEnv/publish?id=<envId>`.
2. The env mutex transitions from `STABLE` to `DEPLOYING`.
3. Studio computes desired-state changes through checksum-gated diff.
4. Studio renders DDL and row changes.
5. The connector applies the change set:
   - `SOFTA`: signed metadata upgrade API, optionally with DDL.
   - `JDBC`: execute DDL against the external database; row changes are ignored.
6. Studio writes a `DesignActivity` and, on success, a `DesignSnapshot`.
7. The env returns to `STABLE`.

Publish is roll-forward only. Canceling a stuck activity releases the mutex but
does not undo runtime DDL or metadata already applied.

### Drift, Import, and Reverse

- `compareDesignWithRuntime` computes a live operator-facing design-vs-runtime
  drift envelope.
- `previewRuntimeDrift` compares the runtime against the latest successful
  publish snapshot.
- `applyDrift` overwrites design-time metadata with the env's current runtime
  state — serving both drift repair and first-time import.
- `seedFromSource` clones a full env design into an empty target env.
- `merge` converges one env's design to another env's design for selected
  aggregate roots or for the whole swept catalog. Merge is **single-direction
  overwrite** (source → target, no three-way merge): target-only edits are
  overwritten; recovery is restoring the pre-merge activity snapshot.

For JDBC targets, physical reverse currently reads tables and columns. Index
reverse is still deferred, so incremental JDBC publish to a database that
already has matching indexes may re-emit index DDL.

### Restore

`POST /DesignActivity/restore?id=<activityId>` restores the env design from a
successful activity's snapshot, then publishes that restored design to converge
the runtime. Any succeeded activity that captured a snapshot is restorable —
publish, merge, import, and reverse all snapshot their post-operation design.

## Key APIs

### Model Design

| Endpoint | Description |
| --- | --- |
| `GET /DesignModel/previewDDL?id=` | Preview DDL for the current model and its indexes |
| `POST /DesignField/applyDomain` | Copy a `DesignFieldDomain` into a field as a one-time template |

### Environment

| Endpoint | Description |
| --- | --- |
| `GET /DesignAppEnv/compareDesignWithRuntime?id=` | Live design-vs-runtime drift envelope |
| `GET /DesignAppEnv/previewRuntimeDrift?id=` | Runtime drift from the last publish snapshot |
| `POST /DesignAppEnv/issueKey?id=` | Issue or rotate the SOFTA connector signing keypair |
| `POST /DesignAppEnv/applyDrift?id=` | Overwrite design with the env's runtime state (drift repair / first-time import) |
| `POST /DesignAppEnv/seedFromSource?id=&sourceId=` | Clone an empty env from another env |
| `POST /DesignAppEnv/publish?id=` | Publish env design to its runtime |
| `POST /DesignAppEnv/merge?id=&sourceId=` | Merge source env design into target env design |

### Activity

| Endpoint | Description |
| --- | --- |
| `POST /DesignActivity/retry?id=` | Retry a failed publish by publishing the same env again |
| `POST /DesignActivity/cancel?id=` | Cancel a stuck running activity and release the env mutex |
| `POST /DesignActivity/restore?id=` | Restore design from the activity snapshot, then publish |
| `GET /DesignActivity/changeReport?id=` | Read the activity's aggregate change report |

### App Status

| Endpoint | Description |
| --- | --- |
| `POST /DesignApp/activate?id=` | Activate an app |
| `POST /DesignApp/enterMaintenance?id=` | Put an app into maintenance mode |
| `POST /DesignApp/deprecate?id=` | Deprecate an app |

## Status Enums

### `DesignAppStatus`

`Active` / `Maintenance` / `Deprecated`

### `DesignAppEnvStatus`

`Stable` / `Deploying` / `Importing` / `Merging`

The env status is the per-env mutex. Publish, import, reverse, and merge acquire
it via an atomic optimistic compare-and-set on the env's `version`
(`versionLock`): a single guarded `UPDATE` flips `Stable` to the busy state, a
lost race surfaces as a "busy — retry later" refusal, and the status is released
back to `Stable` when finished or canceled.

### `DesignActivityStatus`

`Running` / `Success` / `Failure` / `Canceled`

Studio operations are synchronous in the current implementation. There is no
`Pending` state and no automatic rollback state.

### `DesignActivityKind`

`Publish` / `Import` / `Reverse` / `Merge`

### `ConnectorType`

`Softa` / `JDBC`

### `DesignAppEnvType`

`DEV` / `TEST` / `UAT` / `PROD`

## Current Gaps

- Desired-state sync currently covers only the five swept meta-models listed
  above. Translation rows, views, and navigation are design-time only until the
  sweep model is expanded.
- JDBC reverse does not yet read physical indexes, option sets, comments, or
  non-standard constraints.
- `DesignAppEnv.protectedEnv` is enforced on env delete (a protected env refuses
  deletion) but not yet consulted by publish/merge; `active` is honored when
  defaulting a design write's target env; some connector policy fields are not
  yet enforced by every operation.
- Runtime restore is implemented as roll-forward publish from a prior design
  snapshot; it is not a database rollback.

## AI agent guidance

Operators integrating Studio via Open API should use
[`docs/ai/studio-no-code.md`](../../docs/ai/studio-no-code.md). Framework
contributors editing Java metadata use
[`docs/ai/framework/annotation-lane.md`](../../docs/ai/framework/annotation-lane.md).
See [`docs/ai/README.md`](../../docs/ai/README.md) for the full prompt layout.
