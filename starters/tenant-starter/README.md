# Tenant Starter

Multi-tenancy infrastructure for SaaS applications built on Softa: the
`TenantInfo` registry, tenant lifecycle/status, and the runtime plumbing that
isolates data across `@Model(multiTenant = true)` entities. It also ships
plan/entitlement versioning (版本计费 — which modules a tenant's plan unlocks)
and a separate commerce sub-domain (service catalog, orders, payments).

## Dependency

```xml
<dependency>
  <groupId>io.softa</groupId>
  <artifactId>tenant-starter</artifactId>
  <version>${softa.version}</version>
</dependency>
```

Depends on `softa-web`, `reference-data-starter` (for `Currency` / `CountryRegion`
lookups on `TenantInfo`), and `stripe-java` (payments). Auto-configured by
`io.softa.starter.tenant.TenantAutoConfiguration` (component-scan). Requires
Redis for the active-tenant cache.

## Enabling

```yaml
system:
  enable-multi-tenancy: true      # the only tenant-specific key; master switch for isolation
```

With this off, `multiTenant` isolation is not applied. Keep it consistent with
your `@Model(multiTenant = true)` usage (see the app authoring
[config guide](../../docs/ai/authoring/config.md)).

## Entities

Under `io.softa.starter.tenant.entity`:

| Entity | Purpose |
|---|---|
| `TenantInfo` | Tenant registry — `code`, `name`, `status` (ACTIVE/SUSPENDED/CLOSED), `defaultLanguage`/`defaultTimezone`/`defaultCurrency`(→`Currency.id`)/`defaultCountry`(→`CountryRegion.id`), `dataRegion`, and a nullable `subscriptionId` (1:1 owner FK → `TenantSubscription`); soft-delete, distributed id. **No plan/lifecycle columns** — the version lives on `TenantSubscription`. |
| `TenantSubscription` | The tenant's owned 1:1 version — `planId` (FK → `Plan`), `lifecycle` (`SCHEDULED` not-yet-effective / `TRIAL`·`SUBSCRIBED`·`GRACE_PERIOD` active / `EXPIRED` → fallback), `effectiveFrom`/`effectiveTo` (`LocalDate`). Owned via `TenantInfo.subscriptionId`; carries no `tenantId` (one row per tenant, not append-only segments) |
| `Plan` / `PlanEntitlement` | System-level plan catalog — code-as-id, `tier` (ordering; lowest = the fallback floor), `active` — plus the module ids each plan entitles. Deployment-authored seed data (no plan id is hardcoded in the starter) |
| `ServiceProduct` | Commerce catalog (`category`, `price`, `duration`, `active`) — a separate sub-domain from plan/entitlement |
| `ServiceOrder` | Orders (`orderNumber`, `orderStatus`, `amount`) |
| `ServiceRecord` | Service execution records |
| `PaymentRecord` | Payments (`paymentMethod`, `paymentStatus`, amounts) |

## Entitlement (versioning / 版本计费)

A tenant's entitled module set is resolved from its 1:1 `TenantSubscription`, never the nav tree — so
tenant-starter needs no user-starter dependency:

- **`EntitlementResolver`** (behind the framework `EntitlementService` SPI) reads
  `TenantSubscription.planId` → `plan_entitlement` → module set, cached in Redis (`entl:{tenantId}`,
  TTL 1h). It gates on **`lifecycle` only** (TRIAL/SUBSCRIBED/GRACE_PERIOD active; EXPIRED degrades) —
  it does **not** read the effective dates, so there is no per-request date comparison / drift.
- **Fallback / floor** = the catalog's **lowest-`tier` plan** — no plan id is hardcoded, so any
  deployment's own plan naming works. No plan seeded → empty entitlement (unpaid = no access); a
  deployment wanting a free floor simply seeds a lowest-tier plan with a base module set. The same rule
  supplies the default plan at provisioning.
- **`SubscriptionExpiryJob`** wires the effective *dates* to the `lifecycle` gate via two symmetric
  passes, each firing at the owning tenant's local midnight (`TenantInfo.defaultTimezone`): **activate**
  a `SCHEDULED` subscription once `effectiveFrom` arrives (→ `SUBSCRIBED`), and **expire** an active one
  once `effectiveTo` passes (→ `EXPIRED`). Each transition fires an entitlement-changed event (evict
  `entl:` + MQ role-grant cleanup). It is **not** `@Scheduled`; the app drives it via **cron-starter**
  (an hourly `sys_cron` row `SubscriptionExpiry`, `CrossTenant`), so tenants spanning 24 UTC hours each
  transition at their own local midnight. `lifecycle` stays the single source of truth the resolver
  reads (the job only *sets* it from the dates; no read-time drift). A row's life:
  `SCHEDULED →(effectiveFrom)→ SUBSCRIBED →(effectiveTo)→ EXPIRED`. A third, **non-transitional** pass
  (`remindUpcoming`) fires **expiry reminders**: for an active subscription a configured number of days
  before `effectiveTo` (default 7 and 1), at the tenant-local reminder hour (default 10:00), it publishes
  a `SubscriptionExpiryReminderEvent` → `SubscriptionExpiryReminderMessage` (softa-base MQ) so a user
  module can email the tenant's admins — it changes no `lifecycle`. It fires **once per tenant-local day**,
  at or after the reminder hour, deduped via `TenantSubscription.lastReminderDate` (so a misfire catch-up or
  manual re-run the same day does not double-send, and a missed reminder hour still sends later that day).
  The message carries a `trial` flag (`lifecycle == TRIAL`) so the notifier can pick trial-vs-renewal wording.
  Cadence overridable via `tenant.subscription.reminder.{hour,days-before}`; the pure `dueReminderDays` seam
  keeps the day/hour/dedup decision clock-free for tests. Expire + remind share one `effectiveTo` query and a
  single batch owner-load (no per-row `TenantInfo` N+1).
- **Provisioning** (`TenantProvisioningService`, behind the shadowed `POST /TenantInfo/createOne`)
  creates the registry row + the owned subscription. A tenant may only be created as `TRIAL` or
  `SUBSCRIBED` (a future `effectiveFrom` parks it as `SCHEDULED` until the job activates it;
  GRACE_PERIOD/EXPIRED are reached only via lapse / the job). Version edits flow
  through the standard Tenant Info form (`POST /TenantInfo/updateOne`, inline `subscriptionId`), which
  the ORM cascade-updates onto `TenantSubscription` and which republishes entitlement — there is no
  separate plan/lifecycle endpoint. `reconcileScheduledStart` then fixes the lifecycle **both ways**:
  a future `effectiveFrom` on an active sub parks it as `SCHEDULED`, and bringing a `SCHEDULED` sub's
  `effectiveFrom` forward to today/past activates it to `SUBSCRIBED` on save (mirroring the job's
  `activateDue`) — so a start-date change takes effect immediately, not only at create or on the next
  hourly job run.

## How isolation works

Tenant identity travels on the request `Context` (`io.softa.framework.base.context`),
managed thread-locally by `ContextHolder`; the auth layer populates
`Context.tenantId` from the session/login. Then the ORM does the rest:

- **Reads** — for a `@Model(multiTenant = true)` entity the ORM automatically adds
  `WHERE tenant_id = :tenantId` (reserved column `tenant_id`).
- **Writes** — `tenant_id` is auto-filled from `Context.tenantId`.
- **Bypass** — `@CrossTenant` (or `Context.crossTenant = true`) skips tenant
  filtering for system operations.
- **Fan-out** — `@PerTenant` runs a `void` method once per **active** tenant, in
  parallel on virtual threads (capped at 100 concurrent to protect the DB pool).

You generally don't call any of this directly — declaring `@Model(multiTenant = true)`
and enabling `system.enable-multi-tenancy` is enough.

## Public API

`TenantInfoService` (the framework SPI, implemented here):

- `List<Long> getActiveTenantIds()` — active tenant ids (Redis-cached).
- `boolean isTenantActive(Long tenantId)` — existence + `ACTIVE` status (cached).
- `void deactivate(Long tenantId)` — move to `SUSPENDED`, evict caches, force
  affected users to re-login.

`ServiceProduct` / `ServiceOrder` / `ServiceRecord` / `PaymentRecord` each have a
standard `EntityService` + `EntityController` (CRUD/query under `/ServiceProduct`,
`/ServiceOrder`, …) — see the app authoring
[controllers-services guide](../../docs/ai/authoring/controllers-services.md).
