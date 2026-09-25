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
`DynamicRoleSyncJob.syncAll()` itself. The job + Role / UserRoleRel entities live here).
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

There are no `softa.user.*` property keys — password and login policy live in
`UserSecurityPolicy` rows, not in `application.yml`.
