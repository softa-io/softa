# Seed Starter

Seed data is the set of rows an application ships with: navigation, permissions, built-in roles, reference
masters, default leave types, and so on. The rows live in JSON or CSV files on the classpath. This
starter lists those files in a manifest, loads them when someone asks, and records every load file by file,
so it can always say which files have run where.

How to write the files themselves (row format, `id` as the seed `preId`, references, scopes) is covered in
[docs/ai/authoring/seed-data.md](../../docs/ai/authoring/seed-data.md). This README covers how the files are
registered, loaded and recorded.

```xml
<dependency>
    <groupId>io.softa</groupId>
    <artifactId>seed-starter</artifactId>
    <version>${softa.version}</version>
</dependency>
```

The starter's models (`SysPreData`, `SeedLoadBatch`, `SeedLoadRecord`) live in `io.softa.starter.seed`. An
environment whose `scanner-scope` lists packages one by one has to include that package, or the scanner never
creates their tables. An environment that runs with an empty `scanner-scope` creates nothing at boot: add the
`seed_load_batch` and `seed_load_record` tables, with their catalog rows, the way that environment adds new
models, before the first load. `sys_pre_data` needs nothing — only the entity's package moved.

## Two kinds of file, two rules

| | Platform file | Tenant file |
|---|---|---|
| Directory | `data-system/` (`PLATFORM_GLOBAL`), `data-platform/` (`PLATFORM_TENANT`) | `data-tenant/` (`TENANT`) |
| Whose rows | Shared models, or the platform tenant (-1) | Each tenant's own copy |
| `mode` in the manifest | `overwrite` (the default) or `startup` | `once` (the default), `append` or `overwrite` |
| Loaded by | The files the build owns (`startup`): every start, wherever they are pending, before the application takes traffic. The others: the platform admin, from the Seed Data page, after a deploy (the first time, by initializing the environment) | Each new tenant's setup, and the platform admin's **Run** for tenants that are already set up |
| When it changes | Loads again, whole (`OVERWRITE`) | `once`: never runs again in a tenant that ran it; new tenants get the new version. `append`: runs again, creating only the rows a tenant lacks. `overwrite`: runs again, whole. The platform admin can **Reapply** any file |
| Write mode | `OVERWRITE` | `ADDITIVE` for `once`, `APPEND` for `append`, `OVERWRITE` for `overwrite` |

Every decision is made per **file**: a file is loaded in a scope or it is not, and no row-level state is
consulted to decide whether to load it. The three write modes (`LoadMode`) apply once a file loads:

- **`OVERWRITE`**: the file is the data. A row the scope already has is rewritten whole, so a field the
  file leaves out is cleared. A seeded row the scope deleted is created again. Seeded children that the
  file no longer lists are deleted; children added by hand carry no binding and are kept.
- **`ADDITIVE`**: a row the scope already has gets only the fields the file names. No child is deleted, and
  a seeded row the scope deleted stays deleted, together with the rows nested in it and any new row that
  refers to it.
- **`APPEND`**: only the rows the scope lacks. A row with no binding in the scope is created, wherever it
  sits in the file — a new child under a row the scope has included. A row the scope has gets nothing
  written to it, whatever the file now says of it. As in `ADDITIVE`, no child is deleted, and a seeded row
  the scope deleted stays deleted, together with the rows nested in it and any new row that refers to it.
  A scope may have the row without its binding — a release script inserted it, or someone added it on a
  page. When the model declares a `businessKey` and the seed row gives all of it, the stored row with that key
  (active or not) is bound to the preId and left as it is, instead of being created a second time against the
  key's unique index, which would stop the scope's run at that file. Several stored rows with the key refuse
  the seed row; a timeline model is not looked up this way.

An inactive row (`active = false`) is not a deleted one: in every mode it is treated like any other row the
scope has, and never inserted a second time.

A row whose `SysPreData` binding is `frozen` is skipped in every mode, together with its children.

**Timeline models** keep their history in the file. Each version is a row of its own, with its own preId and
the date it starts (`effectiveStartDate`), and the model's businessKey is what files the versions under one
entity. A load writes each version the file names: the stored version starting that day is corrected, and any
other date is inserted where it falls, its neighbours cut to fit. Versions the file does not name are left
alone, like rows taken out of a file. A row never carries `sliceId` (the database generates it) or
`effectiveEndDate` (it follows from the next version), and two rows of one entity may not start on the same
day; the loader refuses a file that breaks either rule.

**A tenant runs a file once**, unless its mode says otherwise. An edit to a released `once` file reaches only
the tenants created after it: the tenants that already ran it never run it again, and the page shows the file
there as changed after load. What those tenants need goes out another way. New rows go in a new file (a patch)
registered after the original; the next **Run** gives that file to every tenant, and new tenants run both. A
patch only adds rows: each has an `id` of its own, and refers to existing rows by their `preId`. A repeated row
would be written over every tenant's copy of it, and initializing an environment relies on no tenant file
repeating another's row. To change or remove rows tenants already have, edit the file that declares them, for
the tenants created after, and ship a data migration in the release, for the tenants that have them.

A file in mode `append` takes its new rows itself: added to the file, they reach every tenant on the next
**Run**, created wherever a tenant lacks them, and no patch is needed. A changed or removed row still needs a
data migration, because an append run writes nothing to the rows a tenant has. Use it for a file whose rows
stand alone — entries a tenant may edit or delete, each one named by its code, such as mail templates — and not
for one whose new rows act on their own in a tenant that has set things up its own way: a default approval flow
is published the moment it is created, beside the tenant's own.

Mark a file `overwrite` only when nobody inside a tenant edits its rows, as with the built-in roles. Running it
again resets them to the file. To put a file's current content over tenants that already ran it — while the
seed is still being written, or for a tenant whose copy went wrong — the platform admin reapplies it (see
*Reapplying files*); that is an operator's override, not how a release reaches tenants.

## The manifest

`seed-manifest.yml` at the classpath root lists every seed file the application loads. It is read at startup,
and startup fails when the manifest is missing or broken. All of its problems are reported in one message,
so a broken manifest can be fixed in one pass.

```yaml
platform:                                   # loaded whole whenever they change
  - file: data-system/Navigation.json       # shared rows
    mode: startup                           # the build owns it: loads at startup, wherever pending
  - file: data-system/Permission.json
    mode: startup
  - data-platform/MailTemplate.Platform.json  # the platform tenant's own rows, loaded from the page
tenant:                                     # run once in each tenant, unless the file's mode says otherwise
  - files:                                  # every tenant
      - data-tenant/SysSequence.json
      - file: data-tenant/MailTemplate.Default.json
        mode: append                        # run again when it changes, creating the rows a tenant lacks
      - file: data-tenant/Role.Tenant.json
        mode: overwrite                     # run again, whole, whenever the file changes
  - module: leave                           # tenants whose plan includes the module
    countries: [NZ]                         # and that work in one of these countries
    files:
      - data-tenant/LeaveType.NZ.json
```

- **Two lists, and the directory says the rest.** A platform file sits in `data-system/` (shared rows) or
  `data-platform/` (the platform tenant's own rows) and is listed under `platform`; a tenant file sits in
  `data-tenant/` and is listed under `tenant`. An entry is the file's path, or a map with the path under
  `file` when it names a mode.
- **Order is load order.** A platform load takes the `platform` list in order, and each tenant runs the
  `tenant` files in order. A file that refers to rows of another file is listed after that file.
- **`tenant` groups** say which tenants run their files. `module`: only a tenant whose plan entitles the
  module (`EntitlementService`; with no entitlement service, every module's files are due). `countries`: only
  a tenant working in one of them — its default country or a company's. A group with neither is for every
  tenant. Platform files have no group: they are not handed out per tenant.
- **`mode`** says what a file does after a scope has loaded it (`SeedFileMode`). A platform file is
  `overwrite`, the default, or `startup` for the files the build owns (see *Files the build owns*). A tenant
  file is `once`, the default, `append` or `overwrite` (see *Two kinds of file, two rules*).
- **Rejected at startup**: an unknown key (a misspelled `countrys` would otherwise be silently ignored; the
  `auto: true` and `overwrite: true` flags the mode replaced are named with the mode to write instead), a key
  written twice (YAML would keep the second and drop the first one's files), a file in a directory its list
  does not take, a file that is not `.json` or `.csv`, a mode its list does not take (`once` or `append` on a
  platform file, `startup` on a tenant file), the same file name registered twice (the load records name a file
  by its name), a file that is not on the classpath, a `module` that is not one name (a group is for one
  module), a country that is not a two-letter upper-case code (quote `"NO"`: YAML reads it bare as false), and
  blank or repeated entries. Once the application is wired, the startup load also refuses a platform file not
  in mode `startup` that writes a model an in-memory startup snapshot is built from (see *Files the build
  owns*).

The manifest does not reject a seed file that it fails to list. The application checks that in its own
tests, because only the application knows which files on its classpath are seeds and which are templates.
`metadata-starter`'s `data-tenant/SysSequence_default.json`, for one, does not load as it stands.

## Where a file stands

The state of a file in a scope (the platform, or one tenant) comes from the scope's load records and the
checksum (SHA-256) of the file in the running build, with CRLF line endings read as LF — a checkout that
converts line endings does not make every file look changed:

| State | Meaning | Picked up next time |
|---|---|---|
| `NOT_LOADED` | Never loaded here | yes |
| `LOADED` | Loaded, and unchanged since | no |
| `CHANGED` | Loaded, then changed, and it reloads: a platform file, or an `append` or `overwrite` tenant file | yes |
| `CHANGED_AFTER_LOAD` | Loaded, then changed, and it does not reload: a `once` tenant file. Its change reaches new tenants only; existing tenants get new rows from a patch and changed rows from a data migration, or the whole file from a reapply | no |
| `LOADED_BY_NEWER_BUILD` | A file that reloads, last loaded by a build made after the one running, and different here: an older build is deployed, after a rollback. Loading it would put older content over newer | no |
| `FAILED` | The latest attempt failed | yes |

Each record carries the build time of the instance that wrote it (Spring Boot's `BuildProperties`, so the
application's `spring-boot-maven-plugin` needs the `build-info` goal). Without build times on both sides
there is nothing to compare, and a changed file is simply `CHANGED`. To load the older content on purpose
after a rollback, delete every record of that file in that scope (`SeedLoadRecord`, by file name, and tenant
for a tenant file): it then reads as `NOT_LOADED` and loads whole on the next load. Deleting only the newer
build's records is not enough — the older build's record becomes the latest, its checksum matches, and the
file reads as `LOADED` while the database still holds the newer content.

## Platform load

`GET /SeedData/platform` lists the platform files with their states and modes (`startup` for the files the build
owns), the running build (version, build time, start time) and the latest platform load. `POST /SeedData/platform/load`
loads the pending files, leaving out the files the build owns: those load at startup (see *Files the build
owns*), and naming one is refused. Its body `{"fileNames": [...]}` loads only the named files, which must all be
pending, in manifest order; the files left out stay pending. With no body (or no `fileNames`), every pending file
loads. An empty list is refused, because a caller that sends one picked nothing, and so is a file a newer build
loaded (`LOADED_BY_NEWER_BUILD`). An environment's first load initializes it before loading anything (see
*Initializing an environment*).

The files load **in one transaction**: either all of them load or none does. A failure names the file and
the cause, and is recorded against the file. Afterwards the permission snapshots (`perm:*`) and
entitlements (`entl:*`) cached in Redis are cleared. **No restart follows.** What the instances build in memory
from seed rows at startup is built only from the files the build owns, which this load never touches. The cron
leader reads the `SysCron` rows again every 30 seconds, so a changed `SysCron` file reaches the schedule within
that time.

Loading is meant for one person, once per deploy. There is no lock beyond refusing to start while another
seed operation is running.

### Files the build owns (`startup`)

Some platform files are not data an operator looks after but part of the build: the navigation and permissions
generated from the frontend's manifests, the sensitive field sets, the data scope types. The running code
depends on them matching it — a new endpoint is refused until its permission row exists — and nobody changes
their rows on a page. The manifest gives them `mode: startup`.

They load at startup, wherever they are pending (never loaded, changed, or failed last time), before the
application takes traffic. `SeedStartupLoad` runs as a startup convergence of softa-orm's `StartupSnapshots`: once
every bean exists and before the web server opens its port, it loads the pending files as one platform load, and
only then are the in-memory snapshots every request reads — the endpoint index, the sensitive field sets, the
navigation tree, the scope registries — built from their rows, once. A deploy therefore brings its navigation and
permissions with it, and no instance ever holds a snapshot older than its rows: nothing to load from the page,
and no restart after it.

- **One owner per model.** Every platform file writing a model a startup snapshot is built from must be in mode
  `startup`; the startup load refuses a manifest that leaves one to the page, which would change those rows while the
  instances run. Those models then accept writes only during the startup load (softa-orm's
  `StartupSnapshotWriteGuard`): a write at any other time would reach no snapshot until the next start.
- **Only rows nobody changes on a page.** A `startup` file goes over its rows on every deploy that changes it,
  the way a page load would, without anyone choosing it.
- **A failure stops the start.** It is recorded against the file, and the application does not take traffic on
  rows that do not match its build; in a rolling deploy the instance being replaced keeps serving. The next start
  tries again.
- **Instances starting together take turns** on a database lock (`softa:seed-startup:<database>`): one loads the
  files, the others find nothing pending once they hold it.
- **A file a newer build loaded is left alone**, with a warning: after a rollback, the older content does not go
  over the newer one (see *Where a file stands*).
- It does not initialize the environment — the first load from the page does — and it does not wait for another
  seed operation: nothing else loads these files.

During a rolling deploy the instance being replaced keeps serving for a moment over rows the new build has
already loaded, so a change to these files must not break the version it replaces: add freely, and remove or
rename across two releases. Permission snapshots are stamped with the build that computed them, so neither
version serves the other's.

## Tenant runs

- **A new tenant**: the application's setup listener calls `TenantSeedRunner.provision(tenantId)`. The
  tenant runs every file it is due, in the caller's thread. A failure is recorded and thrown, so the
  message is redelivered, and the retry continues where the failed attempt stopped. Once redelivery gives up
  the tenant is left in Draft; tenant-starter's `POST /TenantInfo/retrySetup` announces it again, and the
  run continues from the file that failed.
- **Tenants already set up** (active, suspended or closed): `POST /SeedData/tenants/run` with
  `{"tenantIds": [...]}`, or with no body for every one of them (an empty list is refused, as above). It
  runs in the background, one tenant
  after another; `GET /SeedData/batches/running` follows its progress. The platform tenant (-1) is never
  one of them: its rows are the `PLATFORM_TENANT` files, which load with the platform's.

Each file runs in a transaction of its own, and its record is written in the same transaction as its data.
A tenant stops at its first failure, because later files may refer to the failed one's rows; the other
tenants carry on. A run that was interrupted, or one that failed and has since been fixed, is simply run
again: only what has not succeeded runs. A batch that stops making progress for 10 minutes is treated as
interrupted (its instance went down), and the next operation closes it as `INTERRUPTED`.

When a tenant runs any file, the cached permission snapshots of its users (`perm:{tenantId}:*`) are cleared.

A file that wrote rows announces them with a `SeedFileLoadedEvent`: the tenant, the file, and by model the ids
of the rows it created (`createdRows`) and of the rows an earlier load created that it wrote to (`updatedRows`;
an update that changed nothing does not count). It is published inside the file's transaction, in the tenant's
context with permission checks skipped, so listeners act on the tenant's rows whoever started the run, and a
listener that throws fails the file like a load error would. flow-starter listens for it to publish the default
flows a file brings, and again the ones a reapply rewrites (`FlowDesign` rows).

After a deploy, load the platform files before running the tenants. Tenant files refer to platform rows
(roles to permissions, leave types to countries), and a reference to a row that is not loaded yet fails the
file.

`GET /SeedData/tenants/pending` counts the tenants and files a run of every tenant would cover.
`GET /SeedData/tenants/summary?tenantIds=…` returns per-tenant counts, and
`GET /SeedData/tenants/detail?tenantId=…` lists the files one tenant is due and the state of each.

### Reapplying files

`POST /SeedData/tenants/reapply` with `{"tenantIds": [...], "fileNames": [...]}` runs the named tenant files again
in the named tenants, whatever their states — loaded and unchanged, changed after load, even loaded by a newer
build. Each file loads whole over the tenant's copy, as an `overwrite` file does (`OVERWRITE`): the rows the tenant
has are rewritten from the file, a field the file leaves out is cleared, the seeded rows the tenant deleted are
created again, and the seeded children the file no longer lists are deleted. What the tenant changed in those rows
is lost. This is the platform admin's way past *a tenant runs a file once*: while a seed is still being written and
the tenants of a development or test environment should have what it says now, or for a tenant whose copy went
wrong. A release still reaches existing tenants with patches and data migrations.

- **Both lists are required.** There is no "every tenant" and no "every file", and nothing runs unless every file
  is a tenant file of the manifest that every one of the tenants is due (its module and countries), and every
  tenant is set up.
- **It runs as a run does**: in the background, as a `Reapply` batch; the files in manifest order, whatever order
  they are named in, each in a transaction of its own; a tenant stops at its first failure. Each file is recorded,
  so one that succeeds reads as `LOADED` afterwards, and a failure changes nothing about what a run picks up.
- **What it does not touch**: a row whose binding is `frozen`, and a top-level row the file no longer lists — no
  binding says which file a row came from, so remove that one by hand or with a data migration. A seeded child the
  file no longer lists is deleted through its model's `onDelete` rules, so one that business data refers to under
  `RESTRICT` fails the file, which rolls back.
- **Rewritten flows are published again**: the rows it writes are announced (`updatedRows`), and flow-starter
  publishes the flow designs among them.
- It does not wait for the environment's initialization, which exists so a run does not load files over what
  tenants changed: a reapply does that on purpose.

## Initializing an environment

An environment is initialized once, by its first platform load — from the Seed Data page, like any later load.
Until then tenant runs are refused; setting up a new tenant does not wait for it. The page is in the platform
administrator's menu by then: its own menu and permissions are in files the build owns, loaded at startup (see
*Files the build owns*); that load does not count as the first. Before loading anything, the first load
**records the tenant files tenants already have**:

- An environment that held seed data before this starter recorded loads has tenants with rows but no records,
  so the first run would load every tenant file again over what the tenants changed since. For each tenant,
  every tenant file it already has rows of (it holds a `SysPreData` binding for one of the file's `preId`s) is
  recorded as run, in an `Initialize` batch. **No data is changed.** A file a tenant has nothing of is left for
  the next run, and so are `append` and `overwrite` files: an `append` file's run creates only the rows a tenant
  lacks, and an `overwrite` file runs again anyway. In a new environment there is nothing to record.
- The recording is what initializes the environment, and the load's response says what it did
  (`initialization`). When it fails outright nothing is loaded, and the next load tries again. When the files
  then fail to load, the environment is initialized all the same.

Which files the first load takes:

- **A new environment** loads every platform file still pending.
- **An environment that already holds platform seed data** (system-scope bindings from an earlier loader)
  leaves out the files whose rows were changed on the pages: loading them would put each one over what was
  changed. A first load that names no file is refused there.

No restart follows (see *Platform load*).

## Records

Every operation is a `SeedLoadBatch`: its type (`Platform` / `Tenant` / `Provision` / `Reapply` / `Initialize`), status
(`Running` / `Succeeded` / `PartiallyFailed` / `Failed` / `Interrupted`), counts, time span and the build
version that ran it. Every file run in one scope is a `SeedLoadRecord`: the batch, tenant (none for the
platform), file, checksum, status and error. Both models are read through the generic model API
(`/SeedLoadBatch/searchPage`, …), and the records are what the file states are computed from. Deleting
records puts files back to pending.

## Endpoints

All of them require the platform admin (`@RequireRole(SYSTEM_ROLE_ADMIN)`). Tenant runs are refused until the
environment is initialized, which its first platform load does; a reapply is not (see *Reapplying files*).

| Method | Path | Does |
|---|---|---|
| GET | `/SeedData/platform` | Platform files, their states, the build, the latest load |
| POST | `/SeedData/platform/load` | Load the pending platform files, all or the named ones; the first load initializes the environment |
| GET | `/SeedData/tenants/pending` | Tenants and files a run of every tenant would cover |
| GET | `/SeedData/tenants/summary` | Per-tenant counts for the given tenants |
| GET | `/SeedData/tenants/detail` | One tenant's due files and their states |
| POST | `/SeedData/tenants/run` | Run the pending tenant files, in the given tenants or in all, in the background |
| POST | `/SeedData/tenants/reapply` | Run the named tenant files again in the named tenants, whatever their states, overwriting the tenants' copies, in the background |
| GET | `/SeedData/batches/running` | The operation in progress, or null |

## The seed ledger (`SysPreData`)

One binding row per seeded business row: `(model, tenantId, preId) → rowId`. A binding's scope follows the
seeded model's tenancy: a shared model binds once at system scope, and a multi-tenant model binds once per
tenant. A deleted row keeps its binding, which is how an `OVERWRITE` load knows to create it again and an
`ADDITIVE` or `APPEND` one knows to leave it deleted, and how an `APPEND` load tells the rows a scope lacks from
the ones it has — after binding, by businessKey, a row the scope has without one (see `APPEND` above).
`frozen = true` on a binding protects the row from every load.
The ledger is read through the generic model API, so treat `/SysPreData/*` as operator-only.

## Replaced

- `POST /SysPreData/loadPreSystemData`, `/loadPreTenantData`, `/loadPrePlatformData` and
  `/loadSystemDataByUpload` are removed. Loading goes through the manifest and the endpoints above.
  `SysPreDataService` keeps the loader methods, which now take a `LoadMode`.
- Tenant rebuild (`POST /TenantInfo/rebuild`, which purged a tenant's seed rows and set it up again) is
  removed. A failed setup is retried instead (`POST /TenantInfo/retrySetup`, Draft tenants), which discards
  nothing and continues from the file that failed. Bring an existing tenant up to date with **Run**; an
  environment that predates the records is initialized by its first platform load.
- For code built against the earlier classes, the changes are:
  - `SysPreData`, `SysPreDataService` (and its implementation) and `PreDataFormatParser` moved from
    `io.softa.starter.metadata` to `io.softa.starter.seed`, and the loader methods take a `LoadMode`.
    metadata-starter no longer brings `commons-csv` with it.
  - `TenantProvisionedMessage`, `SeederCompletedMessage` and `TenantProvisionedEvent` lost `rebuild`, and
    `SeederCompletedMessage` lost `success` too: it is sent on success only, and a seeder that fails calls
    `TenantProvisioningStatusService.markSeederFailed(tenantId, seederKey, redeliveryCount)` — the redelivery
    count decides whether the failure is final (the tenant moves to `DRAFT`) or MQ still retries it (the tenant
    stays `INITIALIZING`). `SeederCompletedPublisher.publish`
    takes `(tenantId, seederKey)`. `TenantSeedPurgeService` and `TenantSeedCleaner` are gone, and so is the
    unused `softa.tenant.provisioning.ready-timeout-seconds` property.
  - `CacheService` gained `clearByPrefix`; a custom `CacheService` implementation has to add it.
- The manifest's `bootstrap: true` (a file that loaded on its own once, where it never loaded, and from the
  page after) is replaced by `auto: true` (a file the build owns, loaded at startup wherever pending and never
  from the page); `bootstrap` is now an unknown key. `SeedBootstrap` is replaced by `SeedStartupLoad`,
  `PlatformSeedLoader.loadBootstrapFiles` by `loadAutoFiles`, and `SeedManifest.bootstrapFiles` by `autoFiles`.
  `PlatformSeedStatus.restartRequired` is gone with the restart it announced; `PlatformFileStatus` gained `auto`.
- A file's `mode` replaces the manifest's `auto: true` (now `mode: startup`) and `overwrite: true` (now
  `mode: overwrite`), and brings `mode: append` for tenant files. `auto` and `overwrite` are now unknown keys,
  and the message names the mode to write. `SeedFile.auto()` and `overwrite()` are replaced by `mode()`
  (`SeedFileMode`) and `loadsAtStartup()`, `SeedManifest.autoFiles` by `startupFiles`,
  `PlatformSeedLoader.loadAutoFiles` and `pendingAutoFiles` by `loadStartupFiles` and `pendingStartupFiles`, and
  `PlatformFileStatus.auto` and `TenantFileStatus.overwrite` by `mode`. `LoadMode` gained `APPEND`.
