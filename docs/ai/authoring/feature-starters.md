# Feature Starters

Part of the [Softa app authoring guide](../README.md). The [authoring guides](.)
cover the cross-cutting core every app uses (entities, controllers/services,
queries, config). For a specific capability, add the starter and follow its own
reference README.

Add a starter by declaring the dependency (same version as your other Softa
starters) — see [config.md](config.md):

```xml
<dependency>
    <groupId>io.softa</groupId>
    <artifactId>flow-starter</artifactId>
    <version>${softa.version}</version>
</dependency>
```

| Starter | Use it for | Reference |
|---|---|---|
| `flow-starter` | Node-based workflow engine — JSON-defined flows, conditions, parallel branches, approvals, task executors | [Flow docs](https://softa.io/en-US/docs/features/flow) (behaviour, integration, APIs) · [flow-starter README](../../../starters/flow-starter/README.md) (wiring, extension points) |
| `message-starter` | SMS / email + Pulsar messaging — templates, OAuth2 mail, delivery pipeline, retry, dead-letter, provider routing | [message-starter README](../../../starters/message-starter/README.md) |
| `file-starter` | File management — upload/download, object storage (MinIO / OSS). Backs the `File` / `MultiFile` field types (see [entities.md](entities.md), [queries.md](queries.md)) | [file-starter README](../../../starters/file-starter/README.md) |
| `reference-data-starter` | ISO reference masters — countries (3166-1), currencies (4217), subdivisions (3166-2) | [reference-data-starter README](../../../starters/reference-data-starter/README.md) |
| `seed-starter` | Seed data — a manifest of the app's seed files, the platform load, per-tenant runs, the record of every load (see [seed-data.md](seed-data.md)) | [seed-starter README](../../../starters/seed-starter/README.md) |
| `ai-starter` | LLM chat — multi-provider (OpenAI / Azure / DeepSeek / Anthropic / OpenAI-compatible), conversations, streaming | [ai-starter README](../../../starters/ai-starter/README.md) |
| `cron-starter` | Scheduled task execution | starters/cron-starter README |
| `es-starter` | Elasticsearch CRUD + query | starters/es-starter README |
| `user-starter` | User management | starters/user-starter README |
| `tenant-starter` | Multi-tenancy | starters/tenant-starter README |
| `sentry-starter` | Error tracking + performance tracing via Sentry — events tagged with the request's tenant / trace / user, ERROR logs captured automatically, optional JDBC spans. Inert until a DSN is set | [sentry-starter README](../../../starters/sentry-starter/README.md) |

> The relative links above resolve inside the Softa repo. If you copied this
> guide into a downstream app, find these READMEs in the Softa source or on the
> project docs site — each starter's README is its authoritative reference.
