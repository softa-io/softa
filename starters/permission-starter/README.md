# Permission Starter

Enforce-side permission SDK: a **route-admission interceptor** + a **data-scope
engine** + the data-plane `PermissionService` implementation + the **endpoint /
sensitive-field caches**. It carries **no login / RBAC** of its own — it consumes
a `PermissionInfo` snapshot and the RBAC config through SPIs, so a service can
enforce permissions without bundling user management.

```xml
<dependency>
  <groupId>io.softa</groupId>
  <artifactId>permission-starter</artifactId>
  <version>${softa.version}</version>
</dependency>
```

## Dependency & the hard invariant

`permission-starter` depends only on [`softa-web`](../../framework/softa-web/README.md)
(→ `softa-orm` → `softa-base`). It **must not** depend on
[`user-starter`](../user-starter/README.md).

```
softa-orm  ←  permission-starter  ←  user-starter
```

The layering is acyclic: `user-starter` depends on `permission-starter` (it uses
the engine + caches and implements the SPIs), never the reverse. The invariant
**`permission-starter ⊥ user-starter`** is what lets a pure-enforce microservice
ship the interceptor + engine with **zero** login / OAuth / RBAC.

## What you get

`PermissionStarterAutoConfiguration` is an `@AutoConfiguration` that
`@ComponentScan`s `io.softa.starter.permission`. Put the jar on the classpath and
you get, auto-wired:

- **`PermissionInterceptor`** — route admission (coarse: does this user hold a
  permission mapped to this endpoint?), registered on `/**`.
- **The scope engine** — `ScopeRuleCompiler` + `ScopeApplicabilityResolver` +
  `IdentityScopeCompiler` (data-driven identity scopes) + `ScopeContributor`
  implementations → row-level `Filters`.
- **`EndpointIndex`** (endpoint → required permission) and
  **`SensitiveFieldSetCache`** (model → masked fields), each built once at
  `@PostConstruct` from an SPI (seed data; redeploy to refresh).
- **`EndpointCoverageValidator`** — startup (`ApplicationReadyEvent`) check that
  every Spring MVC handler URL is covered by a permission in the `EndpointIndex`
  (or a public / authenticated-bypass pattern); log-only, never fail-fast.
- **`PermissionServiceImpl`** — the data-plane `PermissionService` that
  `ModelServiceImpl` calls transparently at the CRUD boundary for scope
  row-filtering, field masking and write guards; registered via
  `@Bean @ConditionalOnMissingBean` (an app may override, e.g. a no-op stub).

## Principals

Three, and the third is not a third admin.

| | Endpoint gate | Row scope / field mask / write guard | Where its reach comes from |
|---|---|---|---|
| `SUPER_ADMIN` | platform + shared navs | skipped | the platform's own modules |
| `TENANT_ADMIN` | everything its tenant's plan entitles | skipped | that plan |
| `CONSULTANT` | everything its tenant's plan entitles | **skipped** | that plan, for as long as the grant lasts |

The line the whole design rests on is the split between two predicates on
`PermissionInfo`:

```java
isAdmin()            // SUPER_ADMIN or TENANT_ADMIN — asked by the ENDPOINT and NAV checks
hasFullDataAccess()  // isAdmin() or CONSULTANT     — asked by every DATA-plane check
```

A consultant reads the tenant's data unrestricted — that is what they were brought
in to work on — but the MENUS they get are whatever the tenant bought, no more.
Merging the two predicates would silently sell a tenant's entire menu to whoever
authorized a consultant into it.

`CONSULTANT` is **derived from the acting membership, never stored as a `Role`
row**. A stored role would appear in the tenant's own role management, which this
one is explicitly not supposed to be visible in; and the entitlement cleanup
hard-deletes role grants on a plan downgrade without restoring them, so one
downgrade would strip every consultant in that tenant permanently, with no role
left for anyone to put back. Derived, an upgrade takes effect the moment it is
bought and a downgrade narrows on its own.

Whether the derived principal may still act **is not part of the snapshot** — see
`ConsultantAccessChecker` below and step 5 of route admission.

## The SPI seams

`permission-starter` defines the **contract**; someone else provides the
**data**. `PermissionService` lives in `softa-orm` (because `ModelServiceImpl`
calls it directly); the rest live in `io.softa.starter.permission.spi`.

| SPI | Location | Purpose | Default (this module) / real impl |
|---|---|---|---|
| `PermissionService` | `softa-orm` | data-plane scope / mask / write guard | `PermissionServiceImpl` |
| `ScopeContributor` | `permission-starter.spi` | one scope type → `Filters` | code contributors (Custom / DepartmentSubtree / ManagedDepartments) |
| `PermissionSnapshotProvider` | `permission-starter.spi` | `(tenantId, userId) → PermissionInfo` | `DefaultPermissionSnapshotProvider` (builds via 约定读) — or `RedisPermissionSnapshotProvider` (read-only) for pure-enforce |
| `PermissionEndpointSource` | `permission-starter.spi` | endpoint → permission rows | `DbPermissionEndpointSource` |
| `SensitiveFieldSetSource` | `permission-starter.spi` | sensitive-field-set defs | `DbSensitiveFieldSetSource` |
| `EntitlementService` | `softa-orm` | tenantId → the modules its plan entitles | tenant-starter; **optional** — absent = everything entitled |
| `ConsultantAccessChecker` | `softa-orm` | accountId → may this membership still act, right now | user-starter; **optional** — absent = the question is never asked |

Every SPI ships a **`@ConditionalOnMissingBean` default in this module**, so
permission-starter is self-sufficient: `DefaultPermissionSnapshotProvider` builds
the per-user snapshot from the RBAC config models **by name** (约定读 into view
DTOs), and the `Db*` sources read the endpoint / sensitive-field-set config the
same way. An app that needs different behaviour (a pure-enforce microservice with
an RPC re-sourcer, a no-op stub, …) registers its own bean and the
`@ConditionalOnMissingBean` steps aside.

**`user-starter` and this module stay ⊥** — no compile dependency in *either*
direction, main or test. The last two rows are how a question can still cross that
line: they are declared in `softa-orm`, the layer both sides already depend on, and
injected `required = false`. The engine asks; whoever installed the starter that
knows the answer provides it; a deployment with neither still starts. That is also
why `ConsultantAccessChecker` names a business concept inside the framework — a
deliberate trade, taken because there is no third place for the contract to live,
and recorded on the interface itself.

## Package layout

```
config/       PermissionStarterAutoConfiguration
spi/          PermissionSnapshotProvider · PermissionEndpointSource(+Def)
              SensitiveFieldSetSource(+Def) · ScopeContributor
              PermissionInfo · ScopeRule · ScopeType   (snapshot + scope value types)
spi/support/  AbstractCacheAsideSnapshotProvider · RedisPermissionSnapshotProvider
              DefaultPermissionSnapshotProvider (约定读 build, monolith default)
              DbPermissionEndpointSource · DbSensitiveFieldSetSource
              (JSON columns coerced via softa-base JsonUtils.toStringList)
scope/        ScopeRuleCompiler · ScopeApplicabilityResolver · IdentityScopeCompiler · ScopeFilterTemplates
              DataScopeType (+ data-system/DataScopeType.Builtin.json) · DataScopeTypeReader
              + org-scope support (EmployeeContextEnricher, Department*Resolver, PermissionScopeConfig)
scope/contributor/  Custom · DepartmentSubtree · ManagedDepartments
index/        EndpointIndex · EndpointCoverageValidator
sensitive/    SensitiveFieldSetCache
interceptor/  PermissionInterceptor · PermissionInterceptorProperties · PermissionWebMvcConfig
service/      PermissionServiceImpl
```

## Snapshot re-sourcing

The per-user snapshot is cached under a canonical key — the single source of
truth is `PermissionSnapshotProvider.userSnapshotKey(tenantId, userId)` →
`perm:{tenantId}:user:{userId}`. The producer (`DefaultPermissionSnapshotProvider`)
and every reader use it (user-starter mirrors the shape in `PermissionSnapshotKey`
for its own cache reads / evictions), so the key can never drift.

**Monolith default** — `DefaultPermissionSnapshotProvider` (standalone; its `get()`
carries `@SkipPermissionCheck` so the config reads bypass scope filtering) is a
3-tier cache-aside: request-scoped → Redis (`perm:`, TTL 1h) → **build** from the
RBAC config models by name (约定读 into view DTOs). A user with no active roles
gets a non-null empty-grants snapshot; only a build failure (RBAC models absent /
DB error) fails closed to null.

**Pure-enforce re-sourcing** — `AbstractCacheAsideSnapshotProvider` is the reusable
skeleton (`get()` is `final`):

```
read cache ─ hit  → return
           └ miss → single-flight → resolveOnMiss(t,u) → back-fill → return
                    (any failure → null → caller fails closed)
```

Its one shipped subclass, `RedisPermissionSnapshotProvider`, has `resolveOnMiss →
null` (a fail-closed cache reader — correct when a principal keeps the cache warm).
**Cross-process re-sourcing is deliberately out of the framework**: a pure-enforce
microservice provides its own subclass — forward to the principal's
`GET /me/uiContext`, an internal RPC, a local DB rebuild, … Transport, discovery
and service-to-service auth are **deployment** concerns; the SPI + template are the
framework's whole contribution.

`PermissionEndpointSource` / `SensitiveFieldSetSource` defaults
(`DbPermissionEndpointSource` / `DbSensitiveFieldSetSource`) read the
`Permission` / `SensitiveFieldSet` config by **model name** (convention read,
guarded by `ModelManager.existModel`). These are boot-time config reads, so a
standalone service with DB access needs no extra wiring.

## Scope engine

Scope **types are data**; their compilation is **code only where it must be**.

- **Type registry (data)** — `DataScopeType` (seed `data-system/DataScopeType.Builtin.json`)
  holds each scope type's metadata: `appliesToAll` (ALL / CUSTOM); for **identity
  types** a `filter` template (+ `identityModel` / `identityFilter` for the model-swap);
  for **code-contributor types** an explicit `applicableFields`.
  `ScopeApplicabilityResolver` reads it (via `DataScopeTypeReader`) to answer "which
  scope types apply to model X" — for identity types deriving the applicable fields
  from the `filter` template's field references (`ScopeFilterTemplates.fieldRefs`), so
  applicability and compilation share one source. The role wizard in user-starter
  derives the same answer by reading `DataScopeType` by name, with no engine dependency.
- **Identity scopes → data-driven filter template** — `SELF` / `DIRECT_REPORTS` /
  `CREATED_BY_SELF` are all `<field> = <a value from the caller's
  identity context>`, expressed as a `filter` template whose leaf value is an
  `EnvConstant` placeholder (e.g. `["employeeId","=","USER_EMP_ID"]`).
  `IdentityScopeCompiler` emits that template as a `Filters`; **the placeholder is
  resolved at SQL-build time by `FilterUnitParser`** — the same path `CUSTOM` uses, so
  there is no bespoke `principalSource → value` switch. Model-swap uses `identityFilter`
  on the `identityModel` (e.g. `SELF` filters `id` on `Employee`, `employeeId`
  elsewhere). It fails closed (`new Filters()`) when a required identity value is
  absent — an `EMP_INFO` token (`USER_EMP_ID` / `USER_COMP_ID` / …) with no `EmpInfo`,
  or `USER_ID` with no userId (object-presence check keyed on `EnvConstant`; the value
  itself stays with `FilterUnitParser`). Adding such a type = **an enum value + one
  seed row, no compilation code** (see [Adding a scope type](#adding-a-scope-type)).
- **Code contributors** — types needing runtime I/O or arbitrary expressions keep a
  `ScopeContributor`: `DepartmentSubtree` / `ManagedDepartments` (resolve deptId →
  idPath via `DepartmentIdPathResolver`, build a subtree prefix filter) and `Custom`
  (evaluate an admin-authored `scopeExpr`). These read `Employee` / `Department` by
  model name via `ModelService`; `ModelManager.existModel` lets a non-HR app degrade.

`ScopeRuleCompiler` dispatches each rule: `ALL` → no filter → a registered
`ScopeContributor` → its `compile` → the `IdentityScopeCompiler` data path →
fail-closed. Fail-closed for an inapplicable / empty rule is `WHERE 1=0`
(`ScopeRuleCompiler.matchNone()`), never "no filter".

### No grant: what happens then

A model the caller holds no rule for does **not** simply open. `appendScopeAccessFilters` runs down a
fixed order, and each step is there because the one before it would answer the wrong question:

1. **An explicit rule wins.** Whatever an administrator configured — including a deliberate narrowing —
   is compiled and applied. Nothing below can override it.
2. **Real business data fails closed.** `hasForwardAnchor` asks the registry whether any non-universal
   scope type applies (an employee / department / company field a rule could restrict on). If one does,
   the model is data someone was supposed to grant, and no grant means no rows.
3. **A country value domain is readable.** `multiCountry` + not multi-tenant: a table whose rows are one
   country's allowed values for some field. Row-scoping one is meaningless — the rows are the domain of a
   dropdown, so anyone who can open the form needs all of them — and the country axis already narrows
   them, which is data correctness rather than authorization. **Writes are untouched**; maintaining a
   value domain is gated by the endpoint permission on its page. Seven models qualify today
   (`IdType`, `PassType`, `ResidenceStatus`, `EmploymentType`, `HighestEducationLevel`,
   `HighestEducationTrack`, `WorkPattern`); list them with
   `SELECT model_name FROM sys_model WHERE multi_country = 1 AND multi_tenant = 0` — note the column is
   `tinyint(1)`, so comparing it to `'true'` silently matches the complement.
4. **An anchorless child follows its owner.** `findReferencer` looks through the models the caller *was*
   granted for one pointing at this one, and re-enters scope for that parent.
5. **Otherwise, closed.**

Step 3 exists because steps 2 and 4 between them left a hole. `IdType` is referenced only from
`EmployeeProfile`, which a role reaches *through* `Employee` rather than being granted in its own right
(the wizard does not offer OneToOne children as separately grantable), so step 4 finds no granted
referrer and the read returns nothing — an ID Type dropdown reading "No options available" with the rows
present and the country filter correct. Full write-up, including the three predicates that were measured
and rejected: wiki `Permission-Architecture-v2` 附录 B.

Deliberately **not** covered: `CountryRegion`, `Currency`, `CountrySubdivision`, `Bank`. The first three
are not `multiCountry` and must not be marked so — they *are* the country and currency masters, not data
partitioned by country. `Bank` should be (its rows are per-country) and is pending that change. Until
then they need an explicit grant, as they always have.

**There is no company scope type, and the company axis is not a scope rule.** A `LEGAL_ENTITY`
identity type existed — `["legalEntityId","=","USER_COMP_ID"]` — and was retired, because an identity
template resolves from *the caller*: one role scoped that way granted each holder their own company, so
an HR in company A saw all of A's records and the same role in B saw B's. Which companies a role may
reach is a property of the role. It is configured as the role's ordinary data scope **on the company
model itself** (`role_data_scope` where `model = ModelConstant.COMPANY_MODEL`), which
`DefaultPermissionSnapshotProvider.readGrantedCompanyIds` compiles through this same engine and
materialises into `PermissionInfo.grantedCompanyIds`; `appendCompanyGrant` then bounds every
`@Model(multiCompany)` read by it, and `MultiCompanyScope` narrows within it to the company the header
selected. One row therefore bounds both the company switcher's own list and everything behind it —
before, a separate `RoleCompany` table held the grant and the two could disagree, so narrowing the
company scope while leaving the grant alone still showed every company's departments and reports.
A rule that genuinely wants "the caller's own company" is still expressible as a `CUSTOM` rule naming
`USER_COMP_ID`, where the per-user behaviour is visible in the configuration rather than implied by a
type name. Retiring the type needs its stored rows migrated (`deploy/migrations/mysql/V40`) **before**
the new binary: `ScopeType.valueOf` skips an unknown name silently, and a model left with no rule is not
narrowed at all.

### Adding a scope type

**Identity-shaped** (`<field> = <a value from the caller's context>`) — no
compilation code, two declarations:

1. A `ScopeType` enum value (the stored `scopeType` string is validated by
   `ScopeType.valueOf`; unknown values are skipped, so this is required):
   ```java
   MY_PROJECTS("MyProjects", "Projects I lead")
   ```
2. A `DataScopeType.Builtin.json` seed row with a `filter` template:
   ```json
   { "id": "MY_PROJECTS", "name": "My Projects", "sortOrder": 35,
     "filter": ["projectLeadId", "=", "USER_EMP_ID"] }
   ```

A grant `{"scopeType":"MY_PROJECTS"}` on a model carrying `projectLeadId` then
compiles to `WHERE project_lead_id = <caller.empId>` via `IdentityScopeCompiler` +
`FilterUnitParser` — no contributor, no compiler change (applicability is derived
from the template's `projectLeadId` reference). Need a per-model anchor swap? add
`identityModel` + `identityFilter`. Need an admin-fixed value? author a `CUSTOM`
grant with the value baked into its `scopeExpr` (no longer a type-level column).

> **Why the enum too?** `parseScopeRules` validates the stored code via
> `ScopeType.valueOf` and `ScopeRuleCompiler` dispatches on an `EnumMap`, so a code
> with no enum value is silently dropped. Keeping the enum (rather than a plain
> String code — an intentionally-deferred design, see the split ADR's decision ④)
> is a deliberate trade: `DataScopeType` is **seed data (redeploy to refresh)**, so
> the enum value and the seed row are *both* build-time anyway — dropping the enum
> would save one line, not unlock runtime/no-deploy scope-type addition (that also
> needs `DataScopeType` writable at runtime). The enum buys compile-time safety for
> the code-referenced `ALL` / `CUSTOM` (special-cased in the compiler) and a
> canonical registry of the types the code knows.

The `filter` leaf placeholder must be one the framework already resolves — `USER_ID`
/ `USER_EMP_ID` / `USER_POSITION_ID` / `USER_DEPT_ID` / `USER_COMP_ID` / `NOW` /
`TODAY` / `YESTERDAY` (`EnvConstant` + `FilterUnitParser.convertEnvParameter`). A
**new** principal attribute needs a `Context` / `EmpInfo` field + an `EnvConstant`
token + a `FilterUnitParser` case first — one shared framework registry (also used by
`CUSTOM`), not a per-type switch.

**Anything else** (runtime DB lookup, subtree, arbitrary expression) — write a
`ScopeContributor` (`@Component`; `scopeType()` + `compile()`). The enum value is
still required; the `DataScopeType` row then carries only `applicableFields` (no
`filter`). See `DepartmentSubtreeScopeContributor`.

## Configuration

`PermissionInterceptorProperties` (prefix `permission`):

```yaml
permission:
  public-uri-patterns:            # bypass the interceptor entirely (login / health / …)
    - /login
    - /actuator/health
  authenticated-bypass-patterns:  # authenticated but no permission needed
    - /me/**                      # returns the caller's own data
  platform-only-patterns:         # only SUPER_ADMIN may reach these
    - /Plan/**                    # billing / provisioning / cross-tenant Ops
  platform-nav-prefixes: navigation.system.,navigation.studio.
  shared-nav-prefixes: navigation.message.
```

The two prefix lists are **disjoint, and answer different questions**:
`platform-nav-prefixes` is what a tenant admin is kept OUT of; `shared-nav-prefixes`
is what both audiences are let INTO. A tenant admin's nav set is *every* nav minus
the platform prefixes (fail-open); the platform admin's is *only* the platform
prefixes plus the shared ones (fail-closed). Folding the two lists into one would
name system and studio twice, once under each meaning — the shape that drifts.

Both are read twice, by `DefaultPermissionSnapshotProvider` here and by
user-starter's `UiContextBuilder`, which assembles the same answer for the client.

Framework infrastructure (`/error`, `/actuator/**`, `/swagger-ui/**`,
`/v3/api-docs/**`, `/favicon.ico`) is excluded from the interceptor out of the
box.

## Route admission (`PermissionInterceptor.preHandle`)

1. Public URI → allow.
2. No authenticated user → reject.
3. No tenant on the context → reject (`ConfigurationException`, so monitoring can
   tell a wiring fault from a permission one).
4. Authenticated-bypass pattern → allow (caller's own data).
5. **Consultant whose authorization has ended → 414** (`ConsultantAccessChecker`).
   Asked here, ahead of *every* bypass below, because a consultant's access ends on
   a DATE and the snapshot's own TTL would keep a lapsed one inside a customer's
   tenant. Deliberately after step 4: the client that just learned its authorization
   ended still has to reach `/me/**` and the tenant list to render that.
6. Principal branches — each **bounded**, none a blanket allow:
   - **SUPER_ADMIN** → platform-only patterns exempt, then matched against its own
     snapshot (which `platformAdminSnapshot` narrowed to the platform + shared navs).
   - **TENANT_ADMIN** → platform-only patterns denied, then matched against its
     snapshot (which the tenant's plan narrowed) — this is where 版本计费 is enforced
     for an admin, who holds no static grants for a downgrade to strip.
   - **CONSULTANT** → the same gate as a tenant admin, reached by its own rule: a
     consultant's menus are the tenant's subscription in full. Two rules that agree
     today, not one rule.
   An **unregistered** endpoint still passes all three — plenty of endpoints carry no
   permission mapping, and denying those would turn a billing gate into an outage.
   Closing the mapping gap is `EndpointCoverageValidator`'s job, not this branch's.
7. Endpoint → required permission via `EndpointIndex`; **unmapped endpoint → 403**
   (unknown URLs are denied, not opened) — for everyone who reached this step.
8. Snapshot holds a required permission? allow, else 403.

> **Changed:** the super-admin used to be step 4 and a blanket `return true`. It is
> now step 6, bounded by its snapshot. Tenant business work belongs to the consultant,
> who does it inside the customer that authorized them; the platform administrator
> keeps System and Studio. Matching against the snapshot is what makes that a
> boundary rather than a hidden sidebar — otherwise every tenant endpoint stays one
> typed URL away.

Fine-grained enforcement (row scope + field masking + write guards) then runs in
the data plane via `PermissionService`, transparently inside `ModelServiceImpl`.
