# Demo App

Full-featured demo of the Softa framework. Exercises most starters —
`metadata`, `reference-data`, `es`, `file`, `message`, `flow`, `studio`,
`user`, and `tenant`. Multi-tenancy and Ed25519 request signing are both
enabled, so it doubles as the reference topology for a signed Studio ⇄ runtime
deployment.

This README covers only how to run **this app**. For how the metadata
annotation scanner itself works — `scanner-scope`, the DDL auto-execute policy,
`renamedFrom` handling, the production drift checker, `app_code` identity — see
[`starters/metadata-starter/README.md`](../../starters/metadata-starter/README.md).

## Run locally (dev profile)

### 1. Start infrastructure

```bash
cd deploy/demo-app
docker compose up -d mysql redis
```

`docker-compose.yml` provides **MySQL and Redis only**. The `es` / `file` /
`message` / `flow` / change-log features additionally need Elasticsearch, MinIO,
and Pulsar — start those from their supporting stacks under
[`deploy/`](../../deploy/) if you exercise them.

No SQL seeding: on the first boot against an empty database the catalog
reconcile creates the `sys_*` tables from their own annotations, then the
`["*"]` scanner (step 2) creates every business table. An **existing** dev
database is brought forward the same way on its next boot; hand-written SQL is
needed only for data-carrying changes (see the *DB schema or data change*
branch of [`docs/README.md`](../../docs/README.md)'s decision tree).

### 2. Run the app

```bash
mvn spring-boot:run -pl apps/demo-app -Dspring-boot.run.profiles=dev
```

`application-dev.yml` declares `system.app-code: demo-app` and sets
`system.metadata.scanner-scope: ["*"]`, which activates the
`MetadataAnnotationScanner`: on boot it reconciles every `@Model` / `@Field` /
`@OptionSet` / `@Index` on the classpath against the live `sys_*` rows for this
app and auto-applies the additive DDL. Watch for:

```
MetadataAnnotationScanner: scanner-scope active (matchAll=true), scanning classpath...
MetadataAnnotationScanner: applied N row change(s) to sys_*
DdlOrchestrator: CREATE TABLE currency OK
```

> **Never** set a non-empty `scanner-scope` on a production runtime — leave it
> empty so the read-only `MetadataAnnotationChecker` runs instead. The full
> dev-vs-prod policy lives in the metadata-starter README.

### 3. Verify

Catalog rows are scoped by **`app_code`**, not an `ownership` column (the
ownership tier was retired; annotation and Studio lanes reconcile the same
`sys_*` rows matched by business key).

```sql
SELECT model_name, app_code FROM sys_model
 WHERE app_code = 'demo-app';
```

returns the built-in system models plus the reference-data tables
(`CountryRegion`, `Currency`, …) the scanner reconciled for this runtime.

You can also confirm the runtime binding over HTTP:

```bash
curl -s http://localhost:8080/upgrade/runtime/runtimeInfo | jq .
# → { "appCode": "demo-app", "buildVersion": "...", "databaseType": "..." }
```

## Reference

- [`src/main/resources/application-dev.yml`](src/main/resources/application-dev.yml) — dev config (DB / Redis / `app-code` / scanner-scope / signing key)
- [`starters/metadata-starter/README.md`](../../starters/metadata-starter/README.md) — annotation API + scanner mechanics
- [`docs/ai/framework/annotation-lane.md`](../../docs/ai/framework/annotation-lane.md) — AI/human prompt for `@Model` changes in this repo
- [`docs/ai/`](../../docs/ai/) — AI agent guidance (`authoring/` for apps, `framework/` for the monorepo)
