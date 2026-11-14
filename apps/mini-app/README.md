# Mini App

Minimal runnable Softa application — **`softa-web` only**, no metadata
scanner, no flow, no Studio signing stack. Use it as a lightweight baseline
when testing the web/ORM layer or as a starting point before adding starters.

For the full feature surface (metadata, flow, tenant, reference data, …) see
[`apps/demo-app/README.md`](../demo-app/README.md).

## What is included

| Layer | Status |
|---|---|
| `softa-web` REST / security / async tasks | ✅ |
| MySQL + Redis (via Spring config) | ✅ configured in `application-dev.yml` |
| `metadata-starter` / annotation scanner | ❌ not on classpath |
| Multi-tenancy | ❌ disabled (`enable-multi-tenancy: false`) |

`system.app-code: mini-app` is set for consistency, but without
`metadata-starter` there is no `sys_*` catalog or `/upgrade/runtime/runtimeInfo`
handshake — those require the metadata starter (see demo-app).

## Run locally (dev profile)

### 1. Start infrastructure

```bash
cd deploy/mini-app
docker network create shared-net 2>/dev/null || true
docker compose up -d mysql redis
```

The compose file starts an empty `mini` database — mini-app has no tables of its own.
`application-dev.yml` points at `jdbc:mysql://localhost:3306/db1` — either create
a matching schema locally or align the datasource URL with your MySQL database
name before starting.

### 2. Run the app

```bash
mvn spring-boot:run -pl apps/mini-app -Dspring-boot.run.profiles=dev
```

Default port is **80** (`server.port` in `application-dev.yml`).

### 3. Verify

A successful boot means Spring WebMVC and the configured datasource/redis
connections are healthy. There is no metadata catalog to inspect in this app.

## Adding metadata later

To turn mini-app into an annotation-driven app:

1. Add `metadata-starter` (and any domain starters) to `pom.xml`.
2. Set `system.app-code` and `system.metadata.scanner-scope` in
   `application-dev.yml` (see demo-app).
3. Boot — there is no init DDL to write: the catalog reconcile creates the
   `sys_*` tables from their own annotations, and the scanner creates the
   in-scope business tables.

AI/human prompt for `@Model` changes:
[`docs/ai/framework/annotation-lane.md`](../../docs/ai/framework/annotation-lane.md).

## Reference

- [`src/main/resources/application-dev.yml`](src/main/resources/application-dev.yml) — dev config
- [`deploy/mini-app/docker-compose.yml`](../../deploy/mini-app/docker-compose.yml) — MySQL + Redis stack
