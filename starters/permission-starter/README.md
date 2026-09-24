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

Every SPI ships a **`@ConditionalOnMissingBean` default in this module**, so
permission-starter is self-sufficient: `DefaultPermissionSnapshotProvider` builds
the per-user snapshot from the RBAC config models **by name** (约定读 into view
DTOs), and the `Db*` sources read the endpoint / sensitive-field-set config the
same way. `user-starter` implements **none** of these SPIs — it is fully ⊥ of the
engine (no compile dependency in *either* direction, main or test). An app that
needs different behaviour (a pure-enforce microservice with an RPC re-sourcer, a
no-op stub, …) registers its own bean and the `@ConditionalOnMissingBean` steps
aside.

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
              SubtreeFilterRewriter · IdPath   (a caller-written CHILD_OF → the target's idPath)
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

### A subtree operator in the caller's own filter

Everything below narrows what a caller may see. `appendScopeAccessFilters` does one thing before any
of that, to the filter the caller *wrote*: a `CHILD_OF` naming a ToOne is rewritten onto the target's
`idPath`.

Left alone it is wrong rather than merely unsupported. `CHILD_OF` compiles to a `LIKE` on the column
it names, and that column holds an id, so `department_id LIKE '873%'` matches by numeric coincidence
rather than by tree position. `SubtreeFilterRewriter` moves the condition to where the tree actually
is and splits it into root + descendants — the same two branches `DepartmentSubtree` emits, and for
the same reason (a segment has no trailing separator, so `1/12` is otherwise a prefix of `1/120`).

A relation qualifies by **shape**, not by name: the target has a `STRING` field named `idPath` and a
ToOne back to itself. A second tree therefore works without an edit here, and a plain reference is
left exactly as the caller wrote it. Ids that resolve to nothing — unknown, soft-deleted, another
tenant's — become `matchNone()`, never a dropped condition.

It runs **before** the early returns below, not after. The rewrite answers what the caller asked; it
does not restrict. An administrator and anything under `@SkipPermissionCheck` ask the same question
and need the same answer, so placing it after the bypass would leave exactly them with the broken one.

`IdPath` holds what a materialized path looks like — the field name, the separator, the rule for
appending the suffix to a cascade path, and the two-branch condition itself — shared with the
contributors so they cannot drift. The ORM keeps its own copy of the separator for `PARENT_OF`
(`StringTools.splitIdPath` splits on it to turn a path back into ids), which is below this starter
and not reachable from here; the two have to be changed together.

**What a model must do to be a tree, and what nothing does for it.** The recognition above is the
whole contract: a `STRING idPath`, and a ToOne to itself. Nothing registers, and no annotation says
"tree". But nothing maintains `idPath` either — this starter only ever reads it. The model that owns
the tree computes it on create and, which is the part that gets forgotten, **rebuilds the whole
subtree when a node moves**. A stale path is not detected here; the filter simply answers about the
branch the node used to sit under. A prefix index on the column is likewise the model's business.

**Getting the name wrong fails silently.** `idpath`, `id_path`, `pathIds` — the shape check does not
match, no rewrite happens, and there is no warning. The `CHILD_OF` then reaches the database as
written, as a `LIKE` on the id column, and returns whatever shares leading digits. That is the cost
of recognising a tree by convention rather than by declaration, and the reason the name cannot be a
setting: recognition is built on it.

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
`@Model(multiCompany)` read by it — the grant is the only company narrowing there is; the header-driven
selection that used to narrow within it went with the header switcher. The same build reads the **countries** behind the grant into `PermissionInfo.grantedCountries`
("my countries") — a concrete set even for an unrestricted grant (every company of the tenant),
because an administrator of an SG-only tenant works in SG whatever a value domain was seeded for. The
interceptor bridges both onto the framework `Context` (`grantedCompanyIds` / `grantedCountries`)
beside the role codes, so the ORM can read them without importing this module; `null` there means
"unknown — do not narrow", which is what public and bypass endpoints get. One row therefore bounds both the company switcher's own list and everything behind it —
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
```

Framework infrastructure (`/error`, `/actuator/**`, `/swagger-ui/**`,
`/v3/api-docs/**`, `/favicon.ico`) is excluded from the interceptor out of the
box.

## Route admission (`PermissionInterceptor.preHandle`)

1. Public URI → allow.
2. No authenticated user → reject.
3. Authenticated-bypass pattern → allow (caller's own data).
4. Super-admin → allow.
5. Endpoint → required permission via `EndpointIndex`; **unmapped endpoint → 403**
   (unknown URLs are denied, not opened).
6. Snapshot holds a required permission? allow, else 403.

Fine-grained enforcement (row scope + field masking + write guards) then runs in
the data plane via `PermissionService`, transparently inside `ModelServiceImpl`.
