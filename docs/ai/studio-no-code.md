> **Audience:** operators / integrators driving Studio's no-code Open API — not downstream code generation. To write Java entities, see [`authoring/entities.md`](authoring/entities.md).

# No-Code Lane — AI Agent Prompt Template

You are working in **Softa's no-code lane**: platform metadata definitions authored in
Studio's design workspace instead of in Java source. This is the **Studio channel**
counterpart to the [annotation lane](authoring/entities.md) (`@Model` / `@Field` in
Java).

> **Scope:** per-tenant metadata customization is **out of scope** — there is
> no per-tenant overlay/merge model. A no-code definition is a *whole* definition (a
> model / field / option-set / view that simply has no Java source), applied
> **platform-wide for the app**, not a per-tenant patch layered on another row.

## What this lane is

- **Authoring surface:** Studio's `Design*Controller` REST endpoints. You make HTTP
  calls; you never edit Java, YAML, or commit to git.
- **Storage:** rows in the Studio **per-env `design_*` workspace** (each
  environment owns a full design set, identified by its **business key** scoped to the
  env: `UNIQUE(env_id, modelName/optionSetCode/…)`; no `logicalId`).
- **Runtime effect:** a definition reaches the runtime `sys_*` catalog only when the env
  is **published** (`POST /DesignAppEnv/publish`) through a signed deployment envelope.
  Publish converges that env's runtime to its design.
- **Identity / scope:** everything is scoped by the app's `app_code`, stamped
  server-side. There is no `tenantId` and **no `ownership` column** on current
  `sys_*` / `design_*` catalogs.

## Same catalog as the annotation lane

The no-code and annotation channels reconcile the **same `sys_*` rows**, matched by
**business key** (`modelName` / `fieldName` / `optionSetCode` / `itemCode`, plus
`renamedFrom`) and stamped with `app_code`. There is no per-channel duplication.

Practical rule:

- **No-code** — definitions with **no Java source** (Studio-only models, views,
  navigation).
- **Annotation lane** — definitions that **should be code-owned** (versioned in git,
  shipped as framework/app Java, reviewed via PR).

If a business key already exists because Java annotations define it, prefer the
annotation lane for structural changes. In development, a package in
`scanner-scope` will reconcile annotation-derived metadata at boot and can overwrite
Studio-published drift for that key. In production (`scanner-scope` empty), publish
is the runtime authority — but code-owned definitions still belong in Java.

## CAN / CANNOT

### ✅ CAN
- Create a **whole new no-code Model** (+ its Fields / Indexes) that has no Java source.
- Create a no-code **OptionSet + OptionItems**.
- Create / edit no-code **Views / Navigation**.
- Edit a no-code definition's attributes (`label`, `length`, `required`, `fieldType`, …).
- **Rename** a no-code **field** by updating the **same row**: the rename API captures the
  prior name into `renamedFrom` (single-step), so publish emits an in-place `CHANGE COLUMN`,
  never drop+add. Do **not** delete-and-recreate to rename. (Renaming a **model /
  option-set** — which has children — is not auto-bridged in studio: it is a drop+add gated by
  `autoExecuteDDL`, or a manual `RENAME TABLE` migration.)
- **Delete** a no-code model / option-set — its children (fields/indexes, items)
  cascade-delete within the same env.
- **Publish** an env to its runtime; **merge / seed** one env's design from another (by
  **business key**).

### ❌ CANNOT
- Per-tenant customization (out of scope).
- Edit Java / `@Model` / `@Field` / enum `@OptionItem` (annotation lane).
- Set `appCode` / `envId` in a request body — the server stamps per-env identity.
  `envId` is **set-once**: an update that sends it has it stripped.
- Commit to git (all operations are HTTP → design-DB writes).
- Safely redefine a **code-owned** model/field/option-set here when the Java source and
  `scanner-scope` will reconcile it at boot — edit the annotation instead.

## Endpoint inventory (current Studio controllers)

### Meta write — the primary no-code surface
All extend `AbstractDesignWriteController`, exposing per entity:
`/{Entity}/createOne`, `/createList`, `/updateOne`, `/updateList`, `/deleteById`,
`/deleteByIds` (`*AndFetch` variants return the row). Reads use the generic
`search` / `findById`.

| Base path | Manages |
|---|---|
| `/DesignModel` | no-code model definitions |
| `/DesignField` | model fields |
| `/DesignModelIndex` | model indexes |
| `/DesignOptionSet` | option sets |
| `/DesignOptionItem` | option items |
| `/DesignView` | views |
| `/DesignNavigation` | navigation tree |

### Release / deploy
| Endpoint | Purpose |
|---|---|
| `POST /DesignAppEnv/publish?id=<envId>` | converge this env's runtime to its design (incremental publish) |
| `GET /DesignAppEnv/compareDesignWithRuntime` · `POST /DesignAppEnv/refreshDrift` | drift check (design vs runtime) |
| `POST /DesignAppEnv/applyDrift` · `/importFromRuntime` | import runtime state back into the env's design |
| `POST /DesignAppEnv/seedFromSource` | seed / merge one env's design from another (by **business key**) |
| `POST /DesignDeployment/retry` · `/cancel` · `/deployPlatform` | deployment lifecycle |

> The version / work-item controllers some older drafts referenced
> (`DesignAppVersionController`, `DesignWorkItemController`,
> `DesignDeploymentVersionController`) were **removed** — the version system is retired
> (per-env design). An env's live design *is* the deployable content; there are no
> version freezes.

### Template layer — NOT no-code metadata
`/DesignSqlTemplate`, `/DesignCodeTemplate`, `/DesignField{Db,Code}Mapping`,
`/DesignFieldDomain` (named reusable field domains; renamed from
`DesignFieldTypeDefault`) configure the platform's code/DDL generation — platform-wide
engineering config, normally changed via the annotation lane, not here.

## The publish contract

`publish(envId)` diffs the env's `design_*` rows against its runtime `sys_*` catalog
with the single `DesignAggregateDiffer` (keyed by **business key**, with a single-step
`renamedFrom` bridge for renames; no logicalId) and ships an **incremental**
`MetadataChangeSet` — per-row `UPSERT` / `DELETE`, plus rename-aware DDL — **not** whole
aggregates. What this means for you:

- A **field rename** is an `UPDATE` located by the prior business key (`renamedFrom`) →
  `CHANGE COLUMN`, data preserved. (Rename by delete+create would drop the column's
  data — don't.)
- **DDL runs before rows**; a structure-only change still ships its DDL.
- The apply is **idempotent** (UPSERT / DELETE located by business key) — a retried
  publish is safe.
- The runtime row is located by its **business key** (`findByBusinessKey`); a renamed
  row is found by its prior key (`renamedFrom`). No adoption / id stamping step.

## Scenarios

### S1 — Create a no-code Model + its fields
```http
POST /DesignModel/createOne
{ "modelName": "Promotion", "label": "Promotion", "businessKey": ["code"] }
```
```http
POST /DesignField/createList
[ { "modelName": "Promotion", "fieldName": "code",   "fieldType": "STRING", "required": true },
  { "modelName": "Promotion", "fieldName": "amount", "fieldType": "BIG_DECIMAL" } ]
```
Then publish (S5). Do **not** send `envId` — the write path stamps the per-env identity.

### S2 — Add a field to an existing no-code Model
```http
POST /DesignField/createOne
{ "modelName": "Promotion", "fieldName": "endsOn", "fieldType": "DATE" }
```
Then publish. (If `Promotion` is defined in Java and in `scanner-scope`, add the field
in the annotation lane instead — boot reconcile may overwrite Studio drift.)

### S3 — Rename a no-code field (in place — data preserved)
Update the **same** field row (by `id`); never delete-and-recreate:
```http
POST /DesignField/updateOne
{ "id": 5012, "fieldName": "discount", "columnName": "discount" }
```
The update API captures the prior `fieldName` into `renamedFrom` server-side, so publish
locates the runtime row by that prior key and emits `CHANGE COLUMN old_col discount …`
(data carried), not drop+add.

### S4 — Create an OptionSet + items
```http
POST /DesignOptionSet/createOne   { "optionSetCode": "PromoState", "label": "Promotion State" }
POST /DesignOptionItem/createList
[ { "optionSetCode": "PromoState", "itemCode": "active",  "label": "Active",  "sequence": 1 },
  { "optionSetCode": "PromoState", "itemCode": "expired", "label": "Expired", "sequence": 2 } ]
```
Then publish.

### S5 — Publish + verify / audit
```http
POST /DesignAppEnv/publish?id=<envId>
```
Then confirm the runtime rows and that drift is clear:
```http
POST /DesignAppEnv/refreshDrift?id=<envId>      # should report in-sync after publish
```

## Verification recipe

After each no-code change:

1. **Design row exists & is stamped** — search the `Design*` endpoint (or query
   `design_*`): `env_id` set, business key present
   (`modelName` / `optionSetCode` / …; there is no `logical_id` column).
2. **Publish the env**, then confirm the `sys_*` row has the correct `app_code` and
   business key.
3. **Restart the host app** (when `scanner-scope` is non-empty) — in-scope annotation
   packages reconcile at boot; expect drift if you edited a code-owned key via Studio.
4. **For a rename** — confirm the runtime column was `CHANGE COLUMN`'d (data kept), not
   dropped + re-added.

## Pitfalls — common AI mistakes

1. **Sending `appCode` / `envId` in the body.** The server stamps per-env identity;
   `envId` is set-once (stripped on update). Sending it is at best ignored, at worst
   confusing.
2. **Editing a code-owned business key via Studio.** Same-key rows are shared with the
   annotation lane. If Java + `scanner-scope` own the key, boot reconcile wins — use the
   annotation lane for structural changes.
3. **Renaming a field by delete + create.** That drops the column's data. Update the same
   row — the rename API captures `renamedFrom`, so publish renders an in-place
   `CHANGE COLUMN`. (Model / option-set renames have children and are not auto-bridged →
   migration.)
4. **Forgetting to publish.** `design_*` edits do not reach the runtime until
   `publish(envId)`.
5. **Editing the wrong env.** A write targets one env's workspace. Publish the env you
   edited; to propagate to other envs use `seedFromSource` / merge (which re-keys children
   by **business key**) — do not re-hand-edit each env.
6. **Per-tenant requests.** Out of scope — a per-tenant ask is a product decision, not a no-code write.
7. **Referencing retired `ownership` / `STUDIO_MANAGED` / `PLATFORM_MAINTAINED`.** The
   catalog is scoped by `app_code` + business key only.

## When to escalate to the annotation lane

If the definition should be **code-owned** — versioned in git, shipped as framework/app
Java, reviewed via PR — use the [annotation lane](authoring/entities.md)
(`@Model` / `@Field`). Tell-tale signs:

- "This model/field should exist for the app **out of the box**."
- "Version it in source / review it in a PR."
- "It's framework / system metadata."
- "Rename / change an **enum** constant's identity (`@OptionItem` / `@JsonValue`)."

Reply: *"That's a code-owned platform definition, not a no-code one — I'll switch to the
annotation lane to do it in Java."*
