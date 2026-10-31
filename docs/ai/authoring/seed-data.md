# Shipping Seed Data

Part of the [Softa app authoring guide](../README.md). How to ship reference data
(countries, currencies, default categories, …) as JSON files alongside your app.

The files are **not** picked up from their directory. Each one is registered in
the app's `seed-manifest.yml`, and [seed-starter](../../../starters/seed-starter/README.md)
loads it: the platform files the build owns at startup, the other platform files
when the platform admin loads them after a deploy, tenant files when a tenant is
set up (and on the admin's **Run** for tenants that already exist). The *table
structure* comes from your entity annotations ([entities.md](entities.md)); this
is just the *rows*.

Place files in your module's resources:
- `src/main/resources/data-system/*.json` — shared data, one copy for all tenants (`PLATFORM_GLOBAL`)
- `src/main/resources/data-platform/*.json` — the platform tenant's own rows on multi-tenant models (`PLATFORM_TENANT`)
- `src/main/resources/data-tenant/*.json` — per-tenant defaults, one copy per tenant (`TENANT`)

---

## Register the file

A file that is not in `seed-manifest.yml` never loads. List a platform file
(`data-system/`, `data-platform/`) under `platform`, and a tenant file
(`data-tenant/`) under `tenant`, in the group of tenants it is for — always
**after** every file whose rows it refers to (the order is the load order):

```yaml
platform:
  - data-system/Currency.AllCurrencies.json
  - data-system/CountryRegion.AllCountries.json   # refers to Currency
tenant:
  - files:                      # every tenant
      - data-tenant/LeaveType.Default.json
      - file: data-tenant/MailTemplate.Default.json
        mode: append            # see below; omit for the default, once
  - module: leave               # plan module; omit when every plan gets it
    countries: [NZ]             # omit for every country
    files:
      - data-tenant/LeaveType.NZ.json
```

How a change to a file reaches data that is already loaded depends on its level
and its `mode`:

- **Platform files** are the data. When one changes, the next platform load
  loads it again, whole: a field the file no longer names is cleared, and a
  seeded child the file no longer lists is deleted.
- **Tenant files run once per tenant** (`mode: once`, the default), and what a
  tenant changes afterwards is the tenant's. An edit to a released tenant file
  reaches only tenants created after it, so what existing tenants need goes out
  another way. New rows go in a new file (a patch), registered after the
  original; the admin's next
  **Run** gives the patch to every existing tenant. A patch only adds rows and
  deletes nothing. To change or remove rows tenants already have, edit the file
  that declares them (for new tenants) and ship a data migration in the
  release (for existing ones). The platform admin can also **reapply** a file,
  which loads it whole over the chosen tenants' copies, what they changed
  included: an operator's override for a development or test environment, or a
  tenant whose copy went wrong — not how a release ships a change.
- `mode: append` (tenant files only) runs a tenant file again whenever it
  changes, creating only the rows a tenant lacks: new rows go straight into the
  file, with no patch, and reach existing tenants on the next **Run**. A row a
  tenant has is never written to — its edits stand, and a row it deleted stays
  deleted — so a changed or removed row still needs a data migration. Use it for
  rows that stand alone, such as mail templates, not for rows that act on their
  own once created, such as a default approval flow beside a tenant's own.
- `mode: overwrite`, every platform file's default, makes a tenant file behave
  like a platform file: it runs again, whole, whenever it changes. Use it only
  for rows nobody in a tenant edits, such as the built-in roles.
- `mode: startup` (platform files only) marks the files the build owns: they load
  at startup, wherever new or changed, before the application takes traffic and
  before the in-memory permission and navigation snapshots are built from them,
  and never from the Seed Data page. Every file writing a model those snapshots
  read (`Navigation`, `Permission`, `SensitiveFieldSet`, `DataScopeType`,
  `ModelDefaultScope`) must be in it — startup refuses a manifest that leaves
  one to the page — and mark only files whose rows nobody changes on a page.
  Keep such a change compatible with the release it replaces: during a rolling
  deploy the old instance serves briefly over the new rows.

---

## File naming

`<EntityName>.<Variant>.json`, e.g. `CountryRegion.AllCountries.json`,
`Currency.AllCurrencies.json`, `Department.Default.json`.

- `<EntityName>` **must** match a `@Model` class's simple name — the loader
  resolves rows to that entity.
- `<Variant>` is a free label to distinguish files; the loader ignores it.

## JSON format

Top-level object keyed by the model name, value is an array of row objects:

```json
{
  "CountryRegion": [
    { "id": "AD", "name": "Andorra", "alpha3Code": "AND",
      "dialCode": "376", "currencyCode": "EUR", "continent": "EU" },
    { "id": "AE", "name": "United Arab Emirates", "alpha3Code": "ARE",
      "dialCode": "971", "currencyCode": "AED", "continent": "AS" }
  ]
}
```

Rules:
- **Field names are the Java `@Field` names (camelCase)** — `dialCode`, not
  `dial_code`. The loader maps JSON → entity, then the ORM maps to columns.
- **Enum values use the `@JsonValue` code**, not the constant name:
  `"continent": "EU"` ✓, `"continent": "EUROPE"` ✗.
- **`id` is the seed `preId`, always a string.** The loader upserts by
  `(model, preId)` **within the loading scope** — system scope for
  `data-system` files, the loading tenant for `data-tenant` files — in
  `SysPreData`, not by `@Model.businessKey`. For
  `EXTERNAL_ID` models (code-as-id masters such as `CountryRegion` / `Currency`)
  the same value is also the business row's primary key. For generated-id models
  it is tracking-only: the loader removes it before insert, lets the ID strategy
  create the row id, then records the `preId -> rowId` mapping.
- **References use seed ids, resolved in the referenced model's scope.** For
  `MANY_TO_ONE` / `ONE_TO_ONE` / `MANY_TO_MANY` fields, put the referenced row's
  seed `id` (`preId`) in the JSON. The loader resolves it to the actual row id
  before writing, looking the binding up in the scope of the model being
  referenced — not the scope of the load. References may cross the tenancy
  boundary **in either direction**: a tenant seed pointing at a shared model (a
  currency, a country, a navigation), and a shared platform-side table pointing
  at a tenant's row (a ledger, a log, a record not exposed to tenants).
  One combination has no answer and is rejected: a **system-scope load cannot
  resolve a multi-tenant model's `preId`** — those bindings exist once per
  tenant and the load has no tenant to pick. Reference such a row by its
  **actual id** instead; a raw id needs no binding and is written through as-is.
  Consequence: **load system seeds before the tenant seeds that reference
  them** — a not-yet-loaded reference fails the whole file.
- **Required fields**: if `@Field(required = true)` and the row omits it, the load
  fails with a NOT NULL error.
- **Omit audit fields** (`createdTime`, `createdBy`, `updatedTime`, …) — the
  loader fills them.

### Timeline models

A timeline model (`@Model(timeline = true)`: a rate or a parameter that changes
from a date) keeps its whole history in the file, **one row per version**, each
with an `id` of its own and the date the version starts:

```json
{
  "PlatformPayrollParameter": [
    { "id": "payroll.param.sg.OW_Ceiling.2025-01-01", "countryPayrollPackageId": "payroll.package.sg",
      "code": "OW_Ceiling", "value": 7400, "effectiveStartDate": "2025-01-01" },
    { "id": "payroll.param.sg.OW_Ceiling.2026-01-01", "countryPayrollPackageId": "payroll.package.sg",
      "code": "OW_Ceiling", "value": 8000, "effectiveStartDate": "2026-01-01" }
  ]
}
```

- The rows are versions of one entity because they share the model's
  `businessKey` (here the package and the code), not because of their ids. A
  new version gets a new `id`; a date in the `id` is only part of the name.
- Never write `sliceId` (the database generates it) or `effectiveEndDate` (it is
  the day before the next version starts). The loader refuses a row that carries
  either.
- A version is its start date. Changing a row's values corrects that version on
  the next load, and a new date inserts a version where it falls. Two rows of one
  entity starting on the same day are refused.
- A version taken out of the file stays in the database, as any row taken out of
  a file does.

---

## Recipes

**Add a row to a platform file**: insert it in the right file (keep stable `id`
order), validate, build. The next platform load picks the changed file up:
```json
{ "id": "SS", "name": "South Sudan", "alpha3Code": "SSD",
  "dialCode": "211", "currencyCode": "SSP", "continent": "AF" }
```
If it references data that doesn't exist yet (currency `SSP`), add that **first**
in its own file — there's no DB FK, so a dangling reference loads silently.

**Update a row in a platform file**: edit the fields and keep `id` unchanged.
Changing `id` changes the seed `preId`, so the loader treats it as a different
seed row. The next platform load rewrites the row currently bound to that
`preId`.

**Add rows for existing tenants**: write a new tenant file and register it
after the original, instead of editing the released one. Every row in it has
an `id` of its own; an existing row is referred to by its `preId`, never
repeated — a new option item names its set in `optionSetId`. The admin's next
**Run** creates the rows in every existing tenant, and new tenants run the
original and the patch. A file in `mode: append` needs no patch: add the rows
to the file itself, and the next **Run** creates them wherever a tenant lacks
them.

**Change a row tenants already have**: edit it in the file that declares it,
keeping its `id`, so tenants created after get the new value; for the tenants
that already have the row, ship the change as a data migration (SQL) in the
release. Once loaded, the row is the tenant's, and tenants may have changed it:
the migration decides what to overwrite. Existing tenants show the edited file
as changed after load — expected, the migration carried the change. An
`append` file is no different here: its run writes nothing to the rows tenants
have, only creates the ones they lack. Never
repeat the row in a later file: it would be written over every tenant's copy,
and it would defeat the environment's initialization (its first platform
load), which records a file as run wherever a tenant holds one of its rows —
the app's manifest test should reject it (hcm's `SeedManifestTest` does).

**Remove a row**: taking a top-level row out of a file does not delete it from
any database. Only seeded *children* (rows of a `ONE_TO_MANY` nested under a
row) that a platform file no longer lists are deleted on its next load. Delete
the row in the app, or with a data migration — for a tenant file, also take it
out of the file, so tenants created after do not get it. A tenant file retired
whole leaves the manifest, and a data migration removes its rows where they
should go.

**Per-tenant defaults** — put them in `data-tenant/` (not `data-system/`) and
register them at level `TENANT`. Each tenant runs the file once, when it is set
up, and the rows are stamped with that tenant. Each tenant gets its own `preId`
bindings, so the same file loads once per tenant
without touching other tenants' rows. When multi-tenancy is enabled, **what a file
may seed** is enforced in both directions — `data-tenant` files may only seed
`multiTenant = true` models, `data-system` files only shared models, and the loader
rejects a mismatch and rolls the file back. **What a row may reference** is not
restricted by tenancy at all — only by what can be resolved: a `preId` of a
multi-tenant model is unresolvable from a system-scope load (no tenant to pick),
so point at those rows by actual id.

---

## Verify

```bash
# JSON validity
for f in src/main/resources/data-system/*.json; do jq . "$f" >/dev/null && echo "OK: $f" || echo "FAIL: $f"; done
# duplicate seed preIds in a file
jq '.CountryRegion[].id' CountryRegion.AllCountries.json | sort | uniq -d
```
The app's startup check rejects a manifest entry whose file is missing. Start the
app once to confirm, and run the app's own manifest tests (every seed file
registered, no tenant file repeating another's row). After deploy, the Seed
Data page lists the file as pending. The platform admin loads the platform
files, then runs the tenants, and checks the row count.
An environment's first platform load initializes it: before loading, it
records the tenant files tenants already have as run (see the seed-starter
README); until then tenant runs are refused.

---

## Common mistakes

1. **`snake_case` field names in JSON** — use camelCase (the Java field name).
2. **`Enum.name()` instead of `@JsonValue`** — `"EUROPE"` won't deserialize; use `"EU"`.
3. **Including audit fields** — omit them; the loader manages them.
4. **Duplicate seed ids in one file** — inconsistent upsert; check with `jq`.
5. **Changing a seed `id` during an update** — this creates a new `preId`
   binding instead of updating the existing one.
6. **Referencing not-yet-loaded data** — add the referenced rows first.
7. **Editing JSON without re-validating** — a trailing comma is silent until load time.
8. **Wrong directory for the model's tenancy** — a `multiTenant = true` model in
   `data-system/`, or a shared model in `data-tenant/`, is rejected at load time
   when multi-tenancy is enabled.
9. **Loading tenant seeds before the system seeds they reference** — the
   reference resolves in the referenced model's scope, so the shared row has to
   exist first; otherwise the file fails with "preIDs … do not exist".
10. **A system seed naming a multi-tenant model's `preId`** — unresolvable, since
    that binding exists once per tenant; use the row's actual id.
11. **A file missing from `seed-manifest.yml`**: it never loads. A file listed
    before a file it refers to fails, because manifest order is load order.
12. **Editing a released tenant file and stopping there**: tenants that ran it
    never run it again (the page shows it as changed after load), so only new
    tenants get the edit. New rows go in a patch file instead — or straight into
    the file when it is in `mode: append`; a changed or removed row also needs a
    data migration for the tenants that have it, whatever the mode.
13. **`mode: overwrite` on rows tenants edit**: every change to the file resets
    every tenant's copy, edits included.
14. **Running tenants before the platform load**: tenant files refer to
    platform rows, so after a deploy, load the platform files first.
15. **A patch that repeats an existing row's `id`** to change it: it writes
    over every tenant's copy, edits included. Change the row in the file that
    declares it and with a data migration, and keep the patch to new rows.
16. **`mode: append` on rows that act by themselves**: a new default approval
    flow, created in a tenant that set up its own, is published beside it. Keep
    such a file `once`, and give existing tenants the new row with a patch only
    where it is wanted.

Not for: test fixtures (use `src/test/resources/`), or data that needs
transformation from an old schema (write a service-layer migration job instead).
