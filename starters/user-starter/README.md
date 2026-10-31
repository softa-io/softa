# User Starter

People, their memberships of companies, authentication, and security policies for
Softa applications. Handles login (identifier/password, email or mobile
verification code, OAuth2 social login), the two-step company choice that follows
it, the invitation and `/join` flow by which an account comes into existence,
password management and lockout, login auditing, configurable password/session
policies, the RBAC config models the permission engine reads, and consultants —
platform staff with dated access into a client company.

**There is no self-registration**: an administrator creates the membership and
invites the person into it.

## Dependency

```xml
<dependency>
  <groupId>io.softa</groupId>
  <artifactId>user-starter</artifactId>
  <version>${softa.version}</version>
</dependency>
```

Depends on `softa-web`, `reference-data-starter`, and `cron-starter` (**optional** —
powers the built-in `UserMaintenanceCronConsumer` for the nightly `DynamicRoleSync` job; a deployment on
a different scheduler omits it, the consumer stays dormant via `@ConditionalOnClass`, and the app drives
`DynamicRoleSyncJob.syncAll()` itself. The job + Role / UserRoleRel entities live here). The cron row
ships in `data-system/SysCron.UserMaintenance.json`, which the app lists under `platform` in its
`seed-manifest.yml`; an unlisted file never loads, and the job never runs.
Auto-configured by `io.softa.starter.user.UserAutoConfiguration` (component-scans the
module — no enabling flag). Sessions require Redis; password-reset emails require a
`MessageService` (message-starter).

## Entities

All under `io.softa.starter.user.entity`:

**The person, their credentials and their memberships are three different rows.**
Getting this wrong is the single most common misreading of this starter:

- `UserProfile` — the **person**. One row per human, global.
- `UserIdentity` — that person's **credentials**. One row per person, global; this is
  what login resolves and where the password lives.
- `UserAccount` — one **membership**: that person inside one tenant. Unique on
  `(tenantId, profileId)`, so one person can be an employee at company A and a
  consultant for company B — two memberships, one person, one set of credentials.
  That is what the tenant picker after authentication is choosing between.

A session maps to a **membership**, so `Context.userId` is an *account* id, not a
person id. Every question about the person (their password, their other companies)
hops through `profileId` first.

| Entity | Purpose | Key fields |
|---|---|---|
| `UserProfile` | The person | `userId`, `fullName`, `chineseName`, `birthDate`, `gender`, `photoId`, `language`, `timezone` |
| `UserIdentity` | The person's credentials — global, unique | `profileId`, `loginEmail`, `loginMobile`, `password`, `passwordSalt`, `passwordLockedUntil` |
| `UserAccount` | One membership (person × tenant) | `tenantId`, `profileId`, `consultant`, `nickname`, `username`, `email`, `mobile`, `policyId`, `status`, `activationTime`, `locked` (derived) |
| `UserInvitation` | An issued invitation | `tenantId`, `userId`, `email`, `mobile`, `purpose`, `tokenHash`, `status`, `invitedBy`, `sentAt`, `expiresAt`, `acceptedAt` |
| `ConsultantProfile` | Marks a person as platform consultant staff | `profileId`, `active` |
| `ConsultantAuthorization` | One consultant's dated access to one tenant | `profileId`, `tenantId`, `startDate`, `endDate` |
| `UserSecurityPolicy` | Per-account policy | `loginMethods`, `activeDeviceLimit`, `sessionDuration`, `sessionIdleDuration`, `passwordValidDays`, `passwordRetryLimit`, `minLength`, `minLowercase`, `minUppercase`, `minDigits`, `minSpecialChars` |
| `UserAuthProvider` | Social identity link | `userId`, `provider` (`APPLE`/`GOOGLE`/`TIKTOK`/`X`/`LINKEDIN`), `providerUserId` |
| `UserLoginHistory` | Login audit | `userId`, `loginMethod`, `ipAddress`, `userAgent`, `location`, `status` |
| `UserAuthFailure` | Failed-auth audit | `userId`, `failureReason`, `ipAddress`, `userAgent` |
| `UserListViewPreference` | One person's arrangement of one list (see [List arrangements](#list-arrangements)) | `userId`, `viewStateKey`, `modelName`, `columns`, `exportFields`, `exportObjectFields`, `viewSettings` |

Wherever another row references an account or a picker lists accounts, the account
shows as `nickname / username` (`@Model(displayName)`): the person's name and the
login that tells two people of one name apart; an empty one is left out. Either one
finds it in a search (`searchName`).

## Authentication & security

- **Passwords** live on `UserIdentity`, hashed (BCrypt) with a per-person
  `passwordSalt`; never stored or returned in clear text. They belong to the PERSON,
  not to a membership — one password opens every company they belong to.
- **Login identifiers** (`loginEmail` / `loginMobile`) are **globally unique** and
  stored in one canonical spelling — trimmed, lowercased, mobile separators folded.
  `LoginIdentifiers` is that rule, and it must be applied wherever an identifier is
  stored, looked up or hashed, or a row is written in a spelling the lookups cannot
  find. Distinct from the **work contacts** on `UserAccount` (`email` / `mobile`),
  which are HR's per-company data and keep their case.
- **Sessions** are stored in Redis (`SOFTA_SESSION_<id>`) and carried by the
  `SOFTA_SESSION_ID` cookie set on login; `logout` clears it. A session names one
  membership — switching company mints a new one.
- **Password lockout** lives on the credential (`UserIdentity.passwordLockedUntil`),
  so it is linked across every company the person belongs to. `UserAccount.locked`
  is a **derived, read-only** projection of it for the account list — writes that
  carry it are silently dropped.
- **Account status** is a single six-value axis (`AccountStatus`): `PENDING`,
  `INVITED`, `ACTIVE`, `FROZEN`, `LOCKED`, `DEACTIVATED`. Freeze/Unfreeze is an
  administrator's decision about a membership; the password lockout is automatic,
  lives on the credential and expires by itself. They are two mechanisms for two
  different things and are deliberately not one field.
- **Password/session policy is entity-driven**, not property-driven: create
  `UserSecurityPolicy` rows and assign them to accounts via `policyId`
  (complexity rules, expiry, device limits, allowed login methods).
- **Auditing** — every login and failure is recorded (`UserLoginHistory` /
  `UserAuthFailure`) with IP, user agent, and location.

## REST API

**There is no self-registration.** An account is created by an administrator and
the person is invited into it; email+password sign-up was removed.

`LoginController` (`/login/*`) — authentication is **two steps**: prove who you are,
then choose which membership to enter.

| Endpoint | Purpose |
|---|---|
| `loginByPassword` | Identifier + password → authenticated, not yet in a tenant |
| `sendEmailCode` / `loginByEmailCode` | Email verification-code login |
| `sendMobileCode` / `loginByMobileCode` | Mobile verification-code login |
| `loginByApple` / `loginByOAuth` | Social login (Apple; Google / TikTok / X / LinkedIn) |
| `listTenants` / `selectTenant` | The company step — spends the single-use pre-auth token for a session |
| `myTenants` / `switchTenant` | The same choice from inside a session |
| `leaveTenant` | Drop this tenant's session and return to the company step, **still authenticated** — what the client calls on a 414 instead of signing the person out |
| `joinEntry` / `sendJoinCode` / `verifyJoinCode` / `setJoinPassword` / `confirmJoin` | The `/join` flow: an invitee proves the contact, sets a password and binds the membership, with no session at any point |
| `inviteInfo` | What the invitation landing page may show for a token |
| `forgetPassword` / `resetPasswordByCode` / `resetPassword` | Verification-code password reset |

`UserAccountController` (`/UserAccount/*`) — the generic CRUD endpoints are all
**shadowed** here so the roster scope applies to by-id reads and writes as well as
to lists, plus:

| Endpoint | Purpose |
|---|---|
| `invite` / `revokeInvitation` | Send or withdraw an invitation |
| `freezeAccount` / `unfreezeAccount` / `unfreezeAccounts` | Suspend a membership (replaces the former Lock/Unlock, which collided with the password lockout) |
| `rehire` | Revive a `DEACTIVATED` membership on its original row |
| `resetWorkContacts` / `unbindAndReinvite` | Correct a mis-addressed or mis-bound invitation |
| `logout`, `getMyAccount`, `saveMyAccount`, `changeMyPassword`, `setMyFirstPassword`, `mustSetMyPassword` | The caller's own membership |

`ConsultantController` (`/consultant/*`) — platform-side, not reachable from a
tenant. `list`, `save` (person + consultant record + the whole grant table in one
transaction), `authorizations`, `setActive`, and `actors` (which of these acting
account ids are consultants, for a tenant's audit trail).

`MeController` (`/me/*`) — `uiContext` returns what the frontend's permission hook reads: role
codes, visible navigations, granted permission ids, the granted sensitive-field-set ids per model
(`modelSensitiveFieldSetsMap`), `modelHiddenFieldsMap` (per model, the sensitive fields none of the
caller's roles grants — empty for a caller with full data access, models with nothing hidden omitted;
only field names, never the set catalogue), the caller's `empId`, and `entitledModules` when
entitlement is installed. `modelHiddenFieldsMap` and `empId` are computed per request, not read from
the cached snapshot. A field hidden only on some records is not in `modelHiddenFieldsMap`; ask
`POST /{model}/getRecordAccess` for those. The same fields are left out of what
`GET /metadata/getMetaModel` serves this caller (metadata-starter README).

## Consultants

Platform implementation staff, authorized into a client company for a bounded
period. A **third principal**, not a third admin — the permission engine's
[README](../permission-starter/README.md#principals) has the gate's half; this
starter owns the data:

- `ConsultantProfile` marks a person as consultant staff and carries the one
  Enabled/Disabled switch covering all of their access at once.
- `ConsultantAuthorization` is one grant: `(profileId, tenantId, startDate,
  endDate)`, unique per pair, **inclusive on both ends**. Saving a grant mints the
  matching `UserAccount` inside the target tenant; revoking one deletes the grant
  and **keeps** the membership, because the tenant's audit log names it as the actor
  of what was done while the access lasted.
- Access expires **by the calendar**, with nobody pressing anything, which is why
  `ConsultantAccessCheckerImpl` answers the engine's per-request question rather
  than letting the permission snapshot's TTL decide.
- Consultant memberships are **hidden from every tenant roster read**
  (`UserRosterScope`): a tenant does not administer them, and an admin who could see
  one could freeze it, leaving the platform's grant saying yes while the membership
  said no.

## Role data scopes

`RoleDataScope` is one role's rules on one model (`dataScopes`, OR-combined) plus an optional
`scopeCondition` — a `Filters` expression AND-ed onto those rules, cascaded fields allowed (e.g. only
employees whose `employeeProfileId.employmentType` is not Contractor). It narrows that role's grant
on the model and nothing else: with permission-starter each role answers for itself, so a condition on
one role never narrows what another role grants. The role wizard saves menus, data scopes (with their
conditions) and sensitive field sets as separate fields of `WizardSaveDTO`, each replacing the stored rows.

## List arrangements

`UserListViewPreference` keeps what a person did to a list — its columns, the columns they added,
their Dynamic Export fields and the list's other view state — on their account, one row per
membership and list. The list is named by the client's view-state key (`viewStateKey`), which the
server compares and never parses. The client builds it as `<view kind>|<model>|<route>|<tab>` —
the kind of view (`table`, `card`, `board`), the model it lists, the page's path as the browser has
it, and the active tab of a tabbed page (empty when there is none, so the key ends in `|`):

| `viewStateKey` | The list |
|---|---|
| `table\|Order\|/sales/order\|` | The order list, a page without tabs |
| `table\|Order\|/sales/order\|pending` | The same page's "pending" tab — its own row |
| `table\|Order\|/sales/order\|shipped` | Its "shipped" tab — another row |
| `card\|Order\|/sales/order\|` | The same orders shown as cards — another row |
| `board\|Task\|/projects/task\|` | A kanban board of tasks |
| `table\|OrderLine\|/sales/order/42\|` | A list inside the page of order 42: the path carries the id, so order 43's page keeps an arrangement of its own |

| Part | Holds |
|---|---|
| `columns` | The columns the person shows, left to right: `[{"id":"fullName","frozen":true},{"id":"code"},{"id":"employeeProfileId.passType"}]`. An id is a field of the list's model, a field one level into a one-to-one record, or a column of the list's own (a pivot column, a cascaded path the page declares); `frozen` is present only when true |
| `exportFields` / `exportObjectFields` | Dynamic Export fields in order, and each related object's, keyed by its collection field |
| `viewSettings` | Other view state (`sidePanelCollapsed` …), saved key by key; a key sent as `null` is removed |
| `columnWidths` | The widths the person dragged columns to, as a share of the table's width in percent: `{"fullName":18.5,"code":10}`. Saved key by key like `viewSettings`; each width is above 0 and at most 100, kept to two decimals. Filtered as the columns are, and cleared with them by `part = columns` |

`UserListViewPreferenceController` (`/UserListViewPreference/*`) has only three endpoints —
`getMyPreference`, `saveMyPreference`, `resetMyPreference` (`part = columns | export | settings`,
all when omitted) — and acts on the caller's own row only. The host registers them in
`permission.authenticated-bypass-patterns`. Every generic model endpoint (`/UserListViewPreference/searchPage`,
`updateOne`, `deleteByIds` …) is declared by the controller too, and refused: left to the generic
`/{modelName}/...` they would serve anyone's row to an admin's permission bypass.
Endpoints that take the model as a parameter rather than in the path — Dynamic Export
(`/export/dynamicExport?modelName=…`) and, with es-starter, the change log (`/ChangeLog/*`) — are
not covered: a caller who holds read or export on the model, an admin through the bypass, can still
read others' arrangements there, though not change them.

**A saved name is never a grant.** Every read and save drops what the caller may not see now: a
field of the list's model by the model's sensitive sets, a related object's field by that object's
model, and a dotted path in two steps — the one-to-one field by the list's model, the leaf by the
record's model and the caller's countries. A column id the metadata cannot judge — a
cascaded path, a column of the list's own such as a pivot column, a field since removed — is kept as
it is for the list to judge: it shows a column only where it can still render one. In the export lists
a path that is not one level into a one-to-one record is dropped, as is a field the metadata no longer has.

**Once saved, the columns are the person's.** Whatever the page declares later does not change them:
a column the page adds is not shown until the person ticks it, and one it stops declaring stays while
the field does. Freezing is marked on the columns, not counted, so a frozen column that goes does not
pull the next one in. Without saved columns, or after a reset, the list shows its own.

**Widths are not an arrangement.** They are kept apart from `columns`, so dragging a column wider on
a list whose columns the person never arranged leaves the list's own columns in charge. A share of
the table rather than pixels, so a column keeps its proportion on a narrower or wider screen.

**Not seeing is not deleting.** A column — or an export field — the caller cannot see *right now* (a
sensitive set withdrawn, a country no longer theirs) is not erased by their next save: it is put back
after the nearest one it followed, so it returns to its place when they can see it again.

**A save writes only the parts it carries**, and a reset only the parts it clears: the side tree and
the columns of one page save at once, and writing the whole row back would undo the other's save.

Limits: 500 names per list (columns included), 128 characters per name, 4000 characters of `viewSettings`.

## Programmatic API

Inject the service interfaces:

- `UserAccountService` — `listMembershipsOf(profileId)` /
  `findMembershipInTenant(tenantId, profileId)` (the person ↔ membership hop),
  `registerInvitedUser(email, mobile, fullName)` (create + invite, which is how an
  account comes into existence), `registerNewUser(accountInfo, profileInfo)`,
  `forceResetPassword(userId, newPassword)` (admin).
- `LoginService` — `generateSessionId(userId)` (creates the Redis session),
  `leaveTenant(currentAccountId)`.
- `ConsultantService` — `isConsultant(profileId)`, `canEnter(profileId, tenantId)`
  (grant **and** the company's own availability), `grantStands(...)` (the grant
  alone), `save(form)`, `setActive(...)`, `replaceAuthorizations(...)`.
- `OAuth2Service` — `loginByApple(idToken)`, `loginByOAuth2(credential)`.

## Configuration

Social login providers (prefix `social-oauth`, bound by `OAuthProperties`); each
provider has an `enable` flag and is off unless configured:

```yaml
social-oauth:
  google:   { enable: true,  client-id: ..., client-secret: ... }
  apple:    { enable: false, client-id: ... }
  tiktok:   { enable: true,  client-id: ..., client-secret: ... }
  x:        { enable: true,  client-id: ..., client-secret: ... }
  linkedin: { enable: false, client-id: ..., client-secret: ... }
```

### First platform administrator

`PlatformAdminBootstrap` creates the platform's first super-administrator at startup, so a fresh
deployment needs no hand-run SQL (prefix `softa.user.platform-admin`, bound by `PlatformAdminProperties`):

```yaml
softa:
  user:
    platform-admin:
      enabled: ${PLATFORM_ADMIN_BOOTSTRAP:false}
      email: ${PLATFORM_ADMIN_EMAIL:}
      password: ${PLATFORM_ADMIN_PASSWORD:}   # from the environment — never commit it
```

- Runs on `ApplicationReadyEvent`, after the boot DDL, so every table it writes exists.
- **First deployment only.** Once the platform tenant has any account holding `SUPER_ADMIN`, it does
  nothing, whatever address is configured — changing the variable never mints a second platform
  administrator. Only a platform left with no super-administrator gets one again, under an address that has
  no account yet, which makes it the way back in.
- **Creates, never repairs.** An account with that address already in the platform tenant is never touched —
  its password above all, or the setting would reset it on every restart — and a revoked role is not
  re-granted.
- **Refuses an address that already signs a person in.** Credentials belong to the person, not the
  account, so the configured password would replace theirs in every tenant they belong to.
- On a platform with no super-administrator, either refusal **fails the boot** and says what to do: carrying
  on would leave nobody able to administer the platform.
- Fails the boot when enabled without an email or password, or when the password fails the password
  policy. Several instances starting together on a fresh database race safely.
- Under multi-tenancy the admin belongs to the platform tenant, which it creates first through
  `TenantInfoService.ensurePlatformTenant` — see tenant-starter's `softa.tenant.platform`.
- The role and the grant are written through `ModelService`: the typed `RoleService` and
  `UserRoleRelService` refuse a coded role and a `SUPER_ADMIN` binding whoever asks, while the write root's
  validators let system paths through. Fields are added through the same services, so they get their
  defaults and validation from the metadata; only a new required field without a default on one of these
  models stops the boot, naming the field.

A fresh deployment, end to end:

1. Start with the three variables set, and under multi-tenancy `softa.tenant.platform`. The scanner creates
   the tables, then the platform tenant and the administrator are created.
2. Sign in as that administrator.
3. Load the platform seed data — the Seed Data page, or `POST /SeedData/platform/load` — and restart every
   instance (see seed-starter's *Platform load*). It loads no seed data itself; the administrator it creates
   is the one who does.
4. Unset the password, or set `PLATFORM_ADMIN_BOOTSTRAP` back to `false`. Startup does not depend on these
   keys afterwards: disabled, it returns before reading anything.

Apart from that, there are no `softa.user.*` keys — password and login policy live in
`UserSecurityPolicy` rows, not in `application.yml`.
