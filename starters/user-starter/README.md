# User Starter

User accounts, authentication, and security policies for Softa applications.
Handles registration and login (email/password, email or mobile verification
code, and OAuth2 social login), password management, account locking, login
auditing, and configurable password/session policies.

## Dependency

```xml
<dependency>
  <groupId>io.softa</groupId>
  <artifactId>user-starter</artifactId>
  <version>${softa.version}</version>
</dependency>
```

Depends on `softa-web` and `reference-data-starter`. Auto-configured by
`io.softa.starter.user.UserAutoConfiguration` (component-scans the module — no
enabling flag). Sessions require Redis; password-reset emails require a
`MessageService` (message-starter).

## Entities

All under `io.softa.starter.user.entity`:

| Entity | Purpose | Key fields |
|---|---|---|
| `UserAccount` | Login identity | `username`, `email`, `mobile`, `password`, `passwordSalt`, `policyId`, `locked`, `status`, `activationTime` |
| `UserProfile` | Personal details | `userId`, `fullName`, `gender`, `photoId`, `language`, `timezone` |
| `UserSecurityPolicy` | Per-account policy | `loginMethods`, `activeDeviceLimit`, `sessionDuration`, `sessionIdleDuration`, `passwordValidDays`, `passwordRetryLimit`, `minLength`, `minLowercase`, `minUppercase`, `minDigits`, `minSpecialChars` |
| `UserAuthProvider` | Social identity link | `userId`, `provider` (`APPLE`/`GOOGLE`/`TIKTOK`/`X`/`LINKEDIN`), `providerUserId` |
| `UserLoginHistory` | Login audit | `userId`, `loginMethod`, `ipAddress`, `userAgent`, `location`, `status` |
| `UserAuthFailure` | Failed-auth audit | `userId`, `failureReason`, `ipAddress`, `userAgent` |

## Authentication & security

- **Passwords** are hashed (BCrypt) with a per-user `passwordSalt`; never stored
  or returned in clear text (`getMyAccount` masks `password`/`passwordSalt`).
- **Sessions** are stored in Redis (`SOFTA_SESSION_<id>`) and carried by the
  `SOFTA_SESSION_ID` cookie set on login; `logout` clears it.
- **Account locking** — `UserAccount.locked` plus `UserSecurityPolicy`
  `passwordRetryLimit` / `passwordRetryInterval` (progressive backoff).
- **Password/session policy is entity-driven**, not property-driven: create
  `UserSecurityPolicy` rows and assign them to accounts via `policyId`
  (complexity rules, expiry, device limits, allowed login methods).
- **Auditing** — every login and failure is recorded (`UserLoginHistory` /
  `UserAuthFailure`) with IP, user agent, and location.

## REST API

`LoginController` (`/login/*`):

| Endpoint | Purpose |
|---|---|
| `registerByPassword` | Register with email + password (auto-login on success) |
| `loginByPassword` | Email/password login |
| `sendEmailCode` / `loginByEmailCode` | Email verification-code login |
| `sendMobileCode` / `loginByMobileCode` | Mobile verification-code login |
| `loginByApple` / `loginByOAuth2` | Social login (Apple; Google / TikTok / X / LinkedIn) |
| `forgetPassword` / `resetPassword` | Email-token password reset |

`UserAccountController` (`/UserAccount/*`): `logout`, `getMyAccount`,
`saveMyAccount`, `changeMyPassword`, `lockAccount` / `unlockAccount` /
`unlockAccounts`.

## Programmatic API

Inject the service interfaces:

- `UserAccountService` — `getUserByEmail` / `getUserByMobile`,
  `registerNewUser(email, mobile, password)`, `forceResetPassword(userId, newPassword)` (admin).
- `LoginService` — `generateSessionId(userId)` (creates the Redis session).
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
