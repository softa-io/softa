# Reference Data Starter

Platform-level reference data for international SaaS applications:

- **CountryRegion** — ISO 3166-1 alpha-2 territories (249 entries)
- **Currency** — ISO 4217 active alpha-3 currencies (155 entries)
- **CountrySubdivision** — ISO 3166-2 first-level subdivisions (entity + service; no seed data this release)
- **Continent** — 7-continent enum used by `CountryRegion`

All rows are **platform-scoped, read-only, and shared across tenants**. There
is no tenant-level override; reference data is the same physical row for
every tenant in the deployment.

## Metadata ownership

The 4 entities (`CountryRegion` / `Currency` / `CountrySubdivision` /
`LanguageProfile`) and the `Continent` enum are all annotated with `@Model` /
`@Field` / `@OptionSet` / `@OptionItem` (see
[`framework/softa-orm`](../../framework/softa-orm/README.md#metadata-annotations)).
`MetadataAnnotationScanner` writes their `sys_*` rows with
`ownership = 'PLATFORM_MAINTAINED'`, so:

- Tenants **cannot** modify model / field structure via Studio Open API.
- Tenants **may** add custom fields (TENANT-owned) onto these models for
  per-tenant extensions.
- Schema drift between the annotations and `sys_*` triggers a startup WARN
  (production) or an automatic ALTER (dev-mode).

The framework `Language` enum (which lives in `softa-base` and cannot carry
`@OptionSet`) is the one exception — its `sys_option_set` row is intended
to be DML-seeded as `ownership = 'PLATFORM_DEFAULT'` so the scanner won't
try to manage it. Tenants typically extend by adding `TENANT`-owned overlay
items rather than modifying the seeded row directly.

## Dependency

```xml
<dependency>
  <groupId>io.softa</groupId>
  <artifactId>reference-data-starter</artifactId>
  <version>${softa.version}</version>
</dependency>
```

## Tables

Apply `src/main/resources/sql/reference-data-starter.sql`:

| Table | Rows | Natural key | Notes |
|---|---|---|---|
| `country_region` | 249 | `code` (ISO 3166-1 alpha-2) | Indexed on `continent`, `currency_code`, `eea` |
| `currency` | 155 | `code` (ISO 4217 alpha-3) | `decimal_places` is critical for monetary arithmetic |
| `country_subdivision` | 0 | `code` (ISO 3166-2) | Schema reserved; populated when address/tax features land |

`country_region.currency_code` is a **concept FK** to `currency.code` (string,
no relational constraint). Same for `country_subdivision.country_code` →
`country_region.code`. Validation happens at the service layer; the database
does not enforce these references because reference data is loaded as
denormalized seed and the cost of `FOREIGN KEY` here is not worth the
constraint guarantees against ops-controlled JSON input.

## Services

| Service | Primary method | Cached |
|---|---|---|
| `CountryRegionService` | `findByCode(String code)` | yes (Redis, TTL 1h) |
| `CountryRegionService` | `findByContinent(Continent)` | no |
| `CountryRegionService` | `findEeaMembers()` | no |
| `CurrencyService` | `findByCode(String code)` | yes (Redis, TTL 1h) |
| `CountrySubdivisionService` | `findByCode(String)`, `findByCountryCode(String)`, `findByParentCode(String)` | no |

`findByCode` is the hot path (called from every SMS send, every tenant
default lookup, etc.); cache is mandatory in production. Cache is invalidated
automatically on `updateOne` / `deleteById` paths through the service.

## Seed Data — JSON + SysPreData

Seed data lives in `src/main/resources/data-system/`:

- `Currency.AllCurrencies.json` — 155 active ISO 4217 currencies
- `CountryRegion.AllCountries.json` — 249 ISO 3166-1 alpha-2 territories
- *(no `CountrySubdivision` JSON yet — entity + service are ready, JSON ships
  with address/tax feature)*

**Seed data is NOT auto-loaded at application startup.** This is by design:

1. Operators retain control over which environments load seed data and when.
2. Reference data may be edited in admin UI (`frozen=true` on `SysPreData`
   protects operator overrides from being overwritten on re-load).
3. Multi-instance deployments avoid load-time race conditions on first start.

To load seed data, call the `metadata-starter` endpoint:

```bash
# Load order matters: Currency first, CountryRegion second
# (CountryRegion.currencyCode references Currency.code).
curl -X POST http://localhost:8080/SysPreData/loadPreSystemData \
  -H 'Content-Type: application/json' \
  -d '["Currency.AllCurrencies.json","CountryRegion.AllCountries.json"]'
```

Idempotent: second call detects existing `preId → rowId` mappings and updates
non-frozen rows in place. Frozen rows are preserved untouched.

**Without `metadata-starter` on the classpath**, the JSON files still ship in
the jar but cannot be loaded via the `/SysPreData` endpoint. Ops must seed
the tables through some other path (direct SQL, custom script).

## Continent enum

`Continent` lives in this starter (not `softa-base`) because its only
consumer is `CountryRegion.continent`. Seven values: `AS`, `EU`, `AF`, `NA`,
`SA`, `OC`, `AN` — the 7-continent model (the most widely-used scheme across
education, UN regional groupings, IAB taxonomies). Codes are project
conventions; they are **not** ISO 3166 / UN M49 codes.

## Consumers in this codebase

| Module | Field / usage |
|---|---|
| `message-starter` | `SmsProviderRegion.regionCode` (concept FK), `dialCode` denormalized from `country_region.dial_code` |
| `framework/softa-orm` | `TenantInfo.defaultCountry` (concept FK to `country_region.code`), `TenantInfo.defaultCurrency` (concept FK to `currency.code`) |

## ISO 4217 fraction digits — quick reference

This is the most common field operators get wrong. Validate against ISO 4217
when loading seed updates:

| `decimal_places` | Currencies (sample) |
|---|---|
| 0 | JPY, KRW, VND, UGX, RWF, PYG, MGA, KMF, XAF, XOF, XPF, BIF, CLP, DJF, GNF, ISK |
| 2 | USD, EUR, GBP, CNY, JPY (no — JPY is 0), AUD, CAD, … (most currencies) |
| 3 | BHD, IQD, JOD, KWD, LYD, OMR, TND |

Mismatching `decimal_places` silently breaks monetary arithmetic, so this is
covered by unit tests against well-known special-case currencies.
