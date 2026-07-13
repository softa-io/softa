# Softa
Metadata-driven, open source enterprise application development framework, including the open source ERP system developed based on this framework.

[Docs](https://www.softa.io/en-US/docs) · [OpenAPI Docs](https://api.softa.io/)

## Design Objectives
1. Focus on Efficiency and Productivity
2. Security and Privacy Protection
3. Flexibility and Scalability

## Key Features
1. Metadata-Driven — entities, fields, and option sets declared via `@Model` / `@Field` / `@OptionSet`
2. Built-in Flow — node-based workflow engine (`flow-starter`)
3. OpenAPI — REST layer generated from metadata
4. Security Controls — signing, encryption, tenant isolation
5. Data Integration — Elasticsearch, messaging, object storage
6. Timeline Model — interval-based versioning on selected entities
7. Multilingual Support
8. Multiple Databases — MySQL, PostgreSQL
9. Multi-Tenancy Support

## Module Structure

```
softa-parent/
├── framework/          # Core: softa-base, softa-orm, softa-web
├── starters/           # Optional features: metadata, flow, ai, studio, …
└── apps/               # Runnable examples: demo-app (full), mini-app (minimal)
```

Dependency direction: `framework` → `starters` → `apps`. See [`CLAUDE.md`](CLAUDE.md) or [`AGENTS.md`](AGENTS.md) for the full module map, build commands, and metadata governance conventions.

## Build & Run

```bash
# Full build (with tests)
mvn clean install

# Build skipping tests
mvn -B -ntp -DskipTests clean install

# Run the full demo locally (dev profile)
mvn spring-boot:run -pl apps/demo-app -Dspring-boot.run.profiles=dev

# Run the minimal demo
mvn spring-boot:run -pl apps/mini-app -Dspring-boot.run.profiles=dev
```

Infrastructure stacks live under [`deploy/`](deploy/) (`deploy/demo-app/docker-compose.yml` for the full stack, `deploy/mini-app/` for the minimal one).

## Metadata Governance (quick pointer)

Platform metadata is declared in Java (`@Model` / `@Field` / `@OptionSet` / `@Index`) and/or authored in Studio's no-code workspace. Both lanes reconcile the same `sys_*` catalog rows matched by **business key**, scoped by **`system.app-code`**. In development, `system.metadata.scanner-scope` controls which packages the boot-time scanner manages and auto-applies DDL for.

| Topic | Where to read |
|---|---|
| Annotation API & scanner | [`starters/metadata-starter/README.md`](starters/metadata-starter/README.md) |
| Annotation properties | [`framework/softa-orm/README.md`](framework/softa-orm/README.md) |
| AI agent prompts (this repo) | [`docs/ai/`](docs/ai/) |
| Manual migrations | [`deploy/migrations/README.md`](deploy/migrations/README.md) |

## Global Placeholder Syntax
Softa uses one placeholder syntax across Flow, document templates, file templates, and DDL generation:

- `{{ expr }}` for dynamic values and expressions
- `{{ TriggerParams.status }}` for simple variable paths
- `{{ @fieldName }}` for reserved field references in Filters

Examples:

```text
{{ TriggerParams.id }}
{{ price * qty }}
{{ NOW }}
{{ @createdTime }}
```

The `{{ }}` convention is consistent across:
- **PlaceholderUtils / TemplateEngine**: runtime placeholder resolution
- **Pebble Template Engine**: DDL generation (see `studio-starter`)
- **AviatorScript**: expression evaluation for computed fields (see `metadata-starter`)

