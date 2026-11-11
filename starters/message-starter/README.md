# Message Starter

Provides unified messaging capabilities for Softa applications:

- **Email**: send emails, receive emails, and render email templates
- **SMS**: send SMS messages, batch send, render templates, and retry on failure
- **Inbox**: push in-app notifications to users

Every mail and SMS is a **send record**, and the record is the delivery queue: a
delivery loop claims due records through an **optimistic-lock CAS** state machine,
so concurrent instances never send a record twice and an in-flight crash loses
nothing (at-least-once: a crash between the provider accepting a message and the
record being marked sent re-sends it once). No broker sits in the delivery path.

## Dependency

```xml
<dependency>
  <groupId>io.softa</groupId>
  <artifactId>message-starter</artifactId>
  <version>${softa.version}</version>
</dependency>
```

## Application API

`MessageService` is the only message-submission service exposed to business
modules:

| Channel | Single | Batch |
|---|---|---|
| Mail | `sendMail(SendMailDTO)` | `sendMailBatch(List<SendMailDTO>)` |
| SMS | `sendSms(SendSmsDTO)` | `sendSmsBatch(List<SendSmsDTO>)` |
| Inbox | `sendInbox(SendInboxDTO)` | `sendInboxBatch(List<SendInboxDTO>)` |

One DTO always represents one independent message. Batch methods accept 1..500
items, join the caller's transaction, and return record IDs in input order. A
mail DTO may address multiple `to` recipients in one MIME message; an SMS DTO
always contains exactly one phone number.

## Requirements and Configuration

The tables are declared by the starter's `@Model` classes: an application whose
`system.metadata.scanner-scope` covers `io\.softa\.starter\.message.*` gets them
created and kept current at boot. Runtime writes use the framework
ORM/versionLock path, so claiming a record does not depend on database-specific
row-lock SQL.

### Hard dependencies

`message-starter` deliberately treats Redis and the relational database as
**hard dependencies**. There is no fail-open / local-fallback path for them —
if either is unavailable the operation that depends on it surfaces an
exception to the caller. This keeps the runtime simple and matches how the
rest of the Softa stack already behaves (cache, distributed lock, session,
etc.). The trade-off and the operational expectations:

| Dependency | Used by | Failure behaviour | Operational expectation |
|---|---|---|---|
| Database | All paths (send records, framework versionLock) | Operation throws; caller sees 5xx | HA-Database (replicated MySQL / managed PG); migrations applied. |
| Redis | `RateLimiter` (per-config delivery rate windows), `MailConfigCache` | Operation throws; caller sees 5xx | Sentinel or Cluster setup. K8s `readinessProbe` should include `/actuator/health/redis` so the load balancer routes traffic away while Redis is unreachable. |
| Pulsar broker | `CronTaskMailFetchConsumer` (scheduled mail fetch), `DeadLetterConsumer` (archiving other consumers' poison messages) | Fetching and poison archiving pause until the broker is back. Sending is unaffected: delivery reads the send records directly. | Optional for sending. |
| SMTP / SMS provider | Outbound send | Per-record fails; classified by `ErrorClassifier`; retried with exponential back-off (`ExponentialBackoffPolicy`). | Configure provider-side rate limits below provider's quota. |

**Why no in-process fallback for Redis?** A local Guava limiter would silently
let one node burst past the cross-node quota during Redis outages, which on a
long enough outage can blow through provider day-quotas and cost real money
(Twilio / Aliyun / SES). It's safer to fail closed at the load-balancer level
via the readiness probe than to silently fan out under partial failure.

### Multi-tenancy

With `system.enable-multi-tenancy` on, the platform's and the tenants' messaging
data live in separate tables, by two rules:

- **Data that belongs to one side only is split by owner.** The platform's copy is
  a `Platform*` model — not multi-tenant, shown on platform menus only — and a
  tenant's is the original model, `multiTenant`, isolated to the caller's tenant
  and stamped by the ORM:

  | Concept | Platform | Tenant |
  |---|---|---|
  | Send server | `PlatformMailSendServer` | `MailSendServerConfig` |
  | Receive server | `PlatformMailReceiveServer` | `MailReceiveServerConfig` |
  | Received mail | `PlatformMailReceiveRecord` | `MailReceiveRecord` |
  | IMAP watermark | `PlatformMailFetchWatermark` | `MailFetchImapWatermark` |
  | Mail template | `PlatformMailTemplate` | `MailTemplate` |
  | SMS provider, country routing, template, template binding | `SmsProviderConfig`, `SmsProviderRegion`, `SmsTemplate`, `SmsTemplateProviderBinding` — not multi-tenant, no tenant column: SMS is a platform service | — |
  | Inbox notification | — | `InboxNotification` |

  Each pair shares its fields through an abstract base class
  (`AbstractMailSendServer` etc.). No read falls back from one table to the other,
  and there is no `@CrossTenant` lookup of a "platform tier" any more.
- **Data the platform handles for everyone is one table that is not
  multi-tenant**, with `tenantId` as an attribution column only: the send
  records (`MailSendRecord`, `SmsSendRecord` — they are the delivery queue), dead
  letters, quotas and usage. The platform's pages read them whole; a tenant reads
  its own mail send log through `TenantMailSendRecord`, a read-only projection
  over `mail_send_record` marked `multiTenant`, which leaves out the bodies and
  template variables. The SMS log has no tenant view.

Behaviour that follows:

- **One code, one tier.** A tenant template may not take a code the platform uses
  (`MailTemplateWriteValidator`, on every write path including seed loading and
  import). A send names the tier it expects with `MessageScope`: `TENANT` renders
  the current tenant's template, `PLATFORM` the platform's — no fallback. A code
  that lives in the other tier fails the record with `TEMPLATE_NOT_RESOLVABLE`
  and a message naming the scope it needs. Tenants receive their templates at
  provisioning from the APPLICATION's tenant seed files (the tenant files of its
  `seed-manifest.yml`, run by seed-starter when the tenant is set up — e.g. by
  hcm's `TenantSeedListener`) and own them from then on; a new template reaches
  existing tenants when the application gives its file `mode: append`, and new
  wording for one they already have goes out as a data migration. The platform
  tenant (`-1`) is a tenant here too: a `TENANT` send it makes (the invitation of
  a new platform staff account) reads its own `MailTemplate` rows. It runs no
  tenant seed files, so those rows go in the application's `data-platform` files.
- **Sending crosses one way only.** A `TENANT` send goes out through the tenant's
  enabled default send server or, when it has none, is relayed through the
  platform's default. A `PLATFORM` send goes out through the platform's only,
  never through tenant-controlled SMTP. The record keeps the server's owner
  (`serverOwner`) with its id, and retries replay the same owner's server.
- **Receiving never crosses.** Received mail belongs to the owner of the server it
  arrived through: the platform's receive servers fill `PlatformMailReceiveRecord`,
  a tenant's fill that tenant's `MailReceiveRecord`. A tenant without a receive
  server of its own gets "not configured" — never the platform's mailbox. Bounces
  and read receipts mark only the send records the same owner's send servers
  carried: the platform's receive server marks the platform's mail and tenants'
  relayed mail (a tenant sees in its send log that a relayed mail bounced, the
  bounce itself stays with the platform, which watches the bounce rate of its own
  servers); a tenant's receive server marks only that tenant's mail sent through
  its own servers. A tenant with a send server but no receive server gets no
  bounces for that mail.
- **Record ownership.** `tenantId` on a send record says whom it is filed under:
  the tenant the send is made for — the calling context's — whatever the scope. The
  scope picks the template and the servers, not the owner: a `PLATFORM` notice about
  a tenant's account, a sign-in code for someone in one company, a subscription
  reminder are that tenant's sends, listed in its send log (without bodies) and
  counted against its quota. A send no tenant can be named for — no context, as for
  the codes of someone in several companies, or the platform's own — is filed under
  `-1`. It is written at acceptance, not stamped by the ORM.
- **Visibility of the shared tables.** The platform's pages — the `Platform*`
  models, `MailSendRecord`, `SmsSendRecord`, the SMS configuration — belong on
  platform menus, and the permission layer is what keeps tenants out of every route
  of them: list them under the application's `permission.platform-nav-prefixes`,
  and permission-starter drops those navigations, and the permissions under them,
  from tenant admins, consultants and a tenant's own roles alike. As a second line
  behind it, the two send logs' list and detail reads and retries refuse a tenant
  caller in code (`TenantScopes.requirePlatformCaller`) — their other generic
  model routes are left to the permission layer — and the status endpoints
  (`/api/mail/status`, `/api/sms/status`) answer a tenant only for its own records.
  An application may also list these models' paths in
  `permission.platform-only-patterns`, which bars tenant admins and consultants at
  the path.
- **A tenant's servers stay on the internet.** A tenant send or receive server
  whose host is, or resolves to, an address inside the deployment (loopback,
  link-local including `169.254.169.254`, private ranges, carrier-grade NAT,
  multicast, IPv6 ULA) is refused on save (`TenantMailServerHostValidator`) and
  again before every connection (`TenantMailHostGuard`), which also catches a name
  that later resolves inward. A host that does not resolve is not refused: the
  connection fails on it and the delivery retries, as for any connection error.
  `softa.message.mail.block-private-tenant-hosts` turns the check off.
- Background jobs scan across tenants and execute per record in the owner's
  context: the delivery loop claims and sends each due record inside its record's
  tenant (and under the trace id of the request that submitted it), the scheduled
  mail fetch runs the platform's receive servers in the platform's context and
  each tenant's in that tenant's, and the zombie sweeper revives each stuck record
  inside its record's tenant.

With multi-tenancy disabled there is one owner: no filtering or stamping occurs,
the scope stops mattering, and every send takes the deployment's own server and
template first, then the platform table's; a pinned server id is looked up in
whichever of the two tables holds it.

### Enable / disable (framework active control)

The config/template models — `MailTemplate` / `PlatformMailTemplate`,
`MailSendServerConfig` / `PlatformMailSendServer`, `MailReceiveServerConfig` /
`PlatformMailReceiveServer`, `SmsTemplate`, `SmsProviderConfig`,
`SmsProviderRegion`, `SmsTemplateProviderBinding` — declare
`@Model(activeControl = true)` with the framework's `active` field — there is
no hand-rolled `isEnabled` switch and no service hand-writes an
`active = true` condition: `WhereBuilder` appends it to every FlexQuery read,
so resolution, dispatch and list surfaces filter consistently and a new query
cannot forget it. Disabling retires a row from every read WITHOUT deleting it,
which is what rows referenced by send records need. Consequences worth
knowing:

- A disabled row leaves the default list view, so each admin page is a two-tab
  MultiView — **Active** (framework default) and **All**
  (`active IN (true, false)`, which names the field and therefore suppresses
  the automatic condition). Without that second tab, disabling a row would
  make it vanish from its own admin page. The column-header filter on `active`
  works the same way for ad-hoc queries.
- Reads that must reach disabled rows use `FilterControl.bypassActiveControl()`
  and say why: authoring tooling (`resolveAny` / `findAnyByCode` — preview and
  variable extraction work before a template is activated) and default demotion
  (`demoteOtherDefaults` — a skipped disabled row would resurface as a second
  default when re-activated). Id-addressed replay needs no bypass: `getById`
  never carries the filter, so an accepted record resolves the server it was
  routed through even after that server is disabled.
- The SMS dispatcher's by-id provider read comes from the config cache, so its
  disabled-route check stays an explicit in-memory guard.

### Send quotas (monthly and daily)

`TenantMessageQuota` (deliberately NOT `multiTenant` — a platform-owned
registry ABOUT tenants, writable only from the platform scope on every write
path — single rows, batches, imports — because `TenantMessageQuotaWriteValidator`
runs at the model service's write roots) sets
per-tenant ceilings for mail/SMS sends: a monthly allowance
(`mailMonthlyLimit` / `smsMonthlyLimit`) and a daily cap (`mailDailyLimit` /
`smsDailyLimit`). The daily cap is there because the platform's send servers and
SMS providers are shared and their own `dailySendLimit` counts every tenant
together: without a day of its own, one tenant's burst could take the whole day
and leave the others nothing until midnight. `SendQuotaGuard` charges a send to
both when it is **prepared** — its first delivery attempt, after its template
rendered and its server or provider resolved, so a send that cannot go out
costs nothing. An over-quota send fails its record with
`DAILY_QUOTA_EXCEEDED` or `MONTHLY_QUOTA_EXCEEDED` (logged at ERROR) — a
commercial ceiling, not rate limiting: nothing queues or retries it — and
retries of a prepared record never touch either count. Retry the record the
next day, or after raising the limit, to send it. Only sends through the platform's
infrastructure are charged: every SMS, and mail a platform send server carries
(the platform's own, and tenants' relayed). A tenant's mail through its own send
server costs the platform nothing and is not counted. The bucket is the tenant
the record is filed under, whatever the scope: a `PLATFORM` notice made for a
tenant draws on that tenant's row and is refused once its quota is spent, like any
of its sends. Sends filed under no tenant draw on the platform's own
`tenantId = -1` row (set it very large; it exists to cap runaway or malicious mass
sending — its daily caps included, set on its row so it never falls back to
the tenants' daily default). Missing rows fall back to
`softa.message.quota.mail-monthly-default` / `sms-monthly-default` /
`mail-daily-default` / `sms-daily-default` (null = unlimited; the counts still
advance for reporting).

The counters live in the **database**: one `TenantMessageUsage` row per
bucket per calendar month (server default zone), check-and-incremented via
the ORM's optimistic-lock CAS — the same `versionLock` pattern as the
delivery state machine — with each attempt in its own `REQUIRES_NEW`
transaction (a lost race retries on a fresh read and can never poison the
delivery attempt; consequence: a preparation that is not written back keeps its
increment — a rare, reconcilable over-count). Every delivery worker of every
instance charges the same row while one bucket's sends are being prepared, so a
lost race is ordinary: after five in a row the attempt gives up as transient
and the record goes to `RETRY` — never `FAILED` as over quota. There is no reset job — a new
month simply starts a new row — and rows are never expired, so per-tenant
consumption history is a plain model query, with the ceiling in force
snapshotted onto each row. The daily count lives in **Redis**: one key per
bucket per channel per calendar day (`quota:{channel}:daily:{tenantId}:{date}`,
expiring after two days), checked and incremented in one Lua script. The day is
charged before the month, so a send the day refuses costs the month nothing; a
send the month refuses — or gives up on over counter contention — is handed back
to the day. A Redis flush forgets the day's count; daily usage keeps no history.
`GET /TenantMessageQuota/usage?tenantId=` serves one bucket's usage this month
and today vs the currently-resolved limits (a tenant session may read only its
own bucket). The per-config `dailySendLimit` / `rateLimitPerMinute`
windows are a different axis — delivery-time infrastructure protection, counted
per server or provider config across every tenant it carries (a platform server
relaying for many tenants stops at its own daily limit, not at that limit once
per tenant). A send over one of them is retried later rather than failed.

### Async delivery (the only delivery model)

Every `MessageService.sendMail(...)` / `sendSms(...)` call follows the same path:

1. **Acceptance** — in the caller's thread and transaction — checks only what
   the caller got wrong: a recipient, well-formed addresses or an E.164 number,
   content or a template to render it from. It writes one `MailSendRecord` /
   `SmsSendRecord` per message, `status = PENDING`, holding the request as it was
   made: any explicit content, the template and its variables, the scope, the
   caller's trace id. The method returns the record id(s) at once; the records
   commit or roll back with the caller's transaction. A rejection throws before
   anything is written and does not mark the caller's transaction rollback-only.
2. The **delivery loop** (`DeliveryPoller`) runs each channel on a worker pool
   (`softa.message.{mail,sms}.send-concurrency`, by default 4 mail and 10 SMS
   workers per instance). A worker
   claims a due record — `PENDING`, or `RETRY` whose `next_retry_at` has passed —
   across tenants with a CAS `PENDING|RETRY → SENDING` through framework
   `versionLock`, sends it in the record's tenant and trace, and goes straight on
   to the next due record. It idles only when nothing is due; every 500 ms the
   poll hands newly due records to idle workers. A backlog therefore drains at concurrency ÷ time
   per send on each instance; raise `send-concurrency` for more, within the mail
   server's limit on concurrent connections per account (each mail worker holds
   one). Each scan reads a few due records per worker and tries them in random
   order, so a claim lost to another worker or instance moves on to the next
   record. A scan is two reads — `PENDING` oldest first by `updated_time`, due
   `RETRY` by `next_retry_at` — each along the index that starts with its status,
   so its cost does not grow with the send log. One `status = … OR …` query
   ordered by id would: on PostgreSQL the generic plan a prepared statement
   settles on walks the primary key through the whole history, idle or not.
3. The channel's `DeliveryProcessor` **prepares** a record on its first attempt:
   it renders the template and applies its defaults, chooses the server (mail)
   or routes the number to a provider (SMS), and charges the day's and the month's quota, last.
   It writes the result back, so retries replay it verbatim. Then it rate-limits,
   sends, and marks the record `SENT` — or hands the failure to
   `SendFailureHandler`: `RETRY` with a back-off `next_retry_at`, `FAILED`, or
   `DEAD_LETTER`.

**A send that cannot go out as configured still has its record.** No server or
provider, a missing or broken template, the daily or monthly quota used up, a
template that renders nothing: the record fails `FAILED` with a marker code
(`CONFIG_NOT_RESOLVABLE`, `TEMPLATE_NOT_RESOLVABLE`, `DAILY_QUOTA_EXCEEDED`,
`MONTHLY_QUOTA_EXCEEDED`, `CONTENT_INVALID`) and the reason, and every terminal failure is logged at ERROR
with the record id and tenant, so log-based alerting reaches a developer. Fix the
configuration, then use the record's Retry action: a record that failed before it
was prepared is prepared afresh. `POST /MailSendRecord/retry?id=` requeues one
record and `POST /MailSendRecord/retryByIds` (body `{"ids": [...]}`) the selected
ones, skipping `SENT` and in-flight `SENDING` records; the SMS endpoints are the
same under `/SmsSendRecord`. The caller never sees these errors — `sendMail`
returned the record id when the request was accepted.

Instances polling the same tables never send a record twice: of two claims
holding the same version only one wins. There is no outbox table and no broker in
the delivery path; Spring `@Async` is not used.

**Calling MessageService.** Every module that sends mail or SMS — user-starter's
login codes, invitations, account notices and subscription reminders included —
depends on message-starter and calls `MessageService` directly; there is no
request event in between. A send is filed under, and counted against, the tenant
the call is made in, whatever its scope; to send for another tenant — a scheduled
job, an anonymous link that names its tenant, a platform notice about one of its
accounts — make the call inside `ContextUtils.inTenantContext(tenantId, …)`. A
send from a context with no tenant is filed under the platform (`-1`). A rejection (`BusinessException`) leaves
the caller's transaction usable; a caller whose own change must not fail because
of a notice catches it and logs it at ERROR, as user-starter's notices do.

### Broker topics

Delivery uses no topic. message-starter consumes two, both optional: `cron-task`
drives the scheduled mail fetch (see *Scheduled Fetch*), and
`softa.message.dlq.topic` collects other consumers' poison messages into the
dead-letter store. Send dead-lettering is a terminal record state (`DEAD_LETTER`)
plus a row archived into the unified `dead_letter_message` store (see *Dead
letter store* below), not a separate queue.

```yml
mq:
  topics:
    cron-task:
      topic: dev_demo_cron_task
      mail-fetch-sub: dev_demo_cron_task_mail_fetch_sub
```

### Upgrading to the split platform and tenant tables

The platform's configuration moves out of the tenants' tables (their `tenant_id = -1`
rows) into `Platform*` tables, and the SMS tables stop being multi-tenant. The
deploying application moves the data; in zingkey-hcm it is
`Release-20261113/20261111-message-split-by-owner`.

- **Before the new jar starts** (old jar stopped): create the five `Platform*` tables
  from their tenant tables without `tenant_id` and move the `-1` rows across with
  their ids — templates only for the codes that become the platform's (a `-1` row with
  any other code is the platform tenant's own tenant template and stays); delete the
  tenants' rows of the four SMS config tables; move the moved rows' `sys_pre_data`
  bindings (to system scope) and their attachments' `file_record.model_name` along;
  add `mail_send_record.server_owner` and backfill it from the table each record's
  `server_config_id` is in. None of it can wait for the scanner: the new jar reads
  only the `Platform*` tables and replays a prepared record's server by
  `server_owner`, and the SMS tables' new unique keys fail on tenant rows.
- **After the new jar**: delete the tenants' templates that use a platform code, and
  `user.contact-reset`; the write validator refuses them from then on.
- Password resets and the three security notices are `PLATFORM` sends now, and the
  platform's pages are new models (`PlatformMail*`, the platform's `MailSendRecord`)
  while a tenant's send log is `TenantMailSendRecord`: menus and role grants follow.

### Message-starter properties

Bound under `softa.message` from `MessageProperties` and the DLQ `@Value` keys.
Only what differs between deployments is configurable. Every instance with
message-starter sends — the delivery loop and the stale-send sweep always run.
SMTP connection reuse is a switch on each server config
(`connectionReuseEnabled`, on by default), not a property. The retry back-off,
the stale-send window, the poll interval, the mail body cap, the SMTP pool's
idle cut-off and messages per connection, and the mail-fetch batch, lease and
size limits are constants in the code that uses them.

```yml
softa:
  message:
    quota:
      mail-monthly-default: 10000   # monthly ceiling for buckets with no TenantMessageQuota row; unset = unlimited
      sms-monthly-default: 10000
      mail-daily-default: 2000      # daily cap for buckets with no TenantMessageQuota row; unset = unlimited
      sms-daily-default: 2000
    dlq:
      topic: dev_demo_message_dlq   # unset = broker-poison archiving disabled
      max-redeliver: 2              # broker nacks before dead-lettering
      alert:
        recipients: ops@example.com # comma-separated; empty = no alert mail
    mail:
      debug: false                  # Jakarta Mail protocol debug, sends and fetches — never enable in prod (leaks AUTH)
      send-concurrency: 4           # mails one instance sends in parallel (each worker holds an SMTP connection)
      block-private-tenant-hosts: true  # refuse a tenant's mail server on an address inside the deployment (multi-tenant only)
      transport:                    # connect + read at most 2m30s, neither zero (checked at startup)
        connection-timeout: 5s      # SMTP/IMAP/POP3 connect timeout
        read-timeout: 30s           # SMTP/IMAP/POP3 read timeout
    sms:
      send-concurrency: 10          # SMS one instance sends in parallel
      transport:                    # same limit as mail
        connection-timeout: 5s      # HTTPS RestClient connect timeout
        read-timeout: 30s           # HTTPS RestClient read timeout
```

### Mail authentication

Mail servers authenticate with **username + password**. Where a provider issues
an API key as its SMTP/IMAP credential, supply that key as the `password`.
Common setups:

- an **ESP / SMTP relay** with an API-key credential (SendGrid, Amazon SES,
  Postmark, Mailgun): set the key as the `password` on `mail_send_server_config`;
- a **provider app password**, where the account issues one;
- a **self-hosted MTA** (e.g. Stalwart, Postfix).

## Email

### Core Logic

#### Config resolution

Email sending uses the following default lookup order:

```text
scope = TENANT   -> 1. the tenant's enabled default send server (MailSendServerConfig)
                    2. the platform's default (PlatformMailSendServer) — the relay for a
                       tenant with none of its own
scope = PLATFORM -> the platform's default only — platform mail never routes
                    through tenant-controlled SMTP
none found       -> BusinessException, logged at ERROR
```

If several rows of one owner are marked default, the one with the smallest
`sequence` is used. Config objects are cached in Redis for 5 minutes, per owner
(`MailConfigCache` keyed by tenant, `PlatformMailConfigCache`), and every write
of a server — whatever endpoint it comes through, the admin pages' generic saves
included — evicts its entry and its owner's default once it commits
(`ConfigCacheEvictor`, a write validator that never refuses anything). A tenant's
server found in the by-id cache is checked to be the caller's own before use.

#### Template resolution

Email templates are resolved by `code` in the tier the send's scope names — one
code lives in one tier, and there is no fallback:

```text
scope = TENANT (default) -> MailTemplate, the current tenant's (code + enabled)
scope = PLATFORM         -> PlatformMailTemplate (code + enabled)
code in the other tier   -> TEMPLATE_NOT_RESOLVABLE, "send it with scope PLATFORM / TENANT"
missing in both          -> TEMPLATE_NOT_RESOLVABLE
```

Template placeholders use the unified Softa syntax: `{{ variable }}`.

#### Body modes

`bodyMode` declares the MIME shape a template (and every record sent from it)
produces — the UI picks editors and the send path picks parts off the same
`BodyMode` vocabulary:

| bodyMode | Sends | bodyHtml | bodyText |
| --- | --- | --- | --- |
| `HTML` | single `text/html` | authored | — |
| `PLAIN` | single `text/plain` | — | authored |
| `HTML_WITH_DERIVED_PLAIN` | `multipart/alternative` | authored | derived from the HTML at accept time (`HtmlUtils.toText`) |
| `HTML_WITH_AUTHORED_PLAIN` | `multipart/alternative` | authored | authored (an empty value falls back to derived and the record is truthfully re-marked `DERIVED`) |

Switching `bodyMode` in the template editor is wired to an onChange handler
(`MailTemplateBodyModeOnChangeHandler`): the existing body is migrated into
the editor the new mode actually sends (HTML → text via `HtmlUtils.toText`,
text → escaped HTML paragraphs; a non-empty target is never overwritten) and
the column the new mode ignores is cleared. Form state only — nothing
persists until save, and Cancel restores the loaded record.

#### Template tooling (editor endpoints)

The Preview & Send Test dialog is backed by three id-addressable operations.
`id` targets the **exact row being edited** — no resolution semantics, no
active-control filter, so a disabled template stays fully inspectable and
testable *before* being enabled; `code` resolves within the caller's own
tier for programmatic callers:

- `GET /api/mail/templates/variables?id=|code=` — the template's distinct
  input tokens in first-appearance order, each classified for the input UI
  (`TemplateVariableKind`): `VARIABLE` (simple names, unicode and dotted
  paths included → one text input), `COLLECTION` (a Pebble `{% for %}`
  iterable → JSON value input), `EXPRESSION` (operands supplied as raw
  JSON), `RESERVED_FIELD` (resolved server-side). Template-local names —
  loop variables, Pebble's builtin `loop`, `{% set %}` targets — are
  excluded: a `{{ item.name }}` inside a loop is not an input.
- `POST /api/mail/templates/preview` — renders subject / bodyHtml / bodyText
  with the given variables, without sending.
- `SendMailDTO.templateId` — a test send addressing the exact row through the
  full production pipeline (record → render → SMTP): what was previewed is
  what goes out. A platform template's test send names `scope = PLATFORM`.

The two `/api/mail/templates/...` endpoints read the tenant's templates; the
platform's template editor uses `GET /PlatformMailTemplate/variables` and
`POST /PlatformMailTemplate/preview`, the same operations over
`PlatformMailTemplate`.

#### Delivery pipeline

Every accepted send produces exactly one `MailSendRecord`. State transitions go
through CAS helpers, so a claim that lost a race self-rejects without a dedupe
table:

```text
PENDING → SENDING → SENT
               ↓
               RETRY → SENDING → SENT
                   ↓
                   DEAD_LETTER (retries exhausted)
               FAILED (permanent: bad recipient, malformed input, auth failure,
                       no server or template, quota used up — first failure,
                       no retry burn)
```

The first attempt prepares the record (see *Async delivery* above): a template
it cannot load or render, a server it cannot resolve, a used-up daily or monthly
quota or an empty rendered body fails it with `TEMPLATE_NOT_RESOLVABLE`,
`CONFIG_NOT_RESOLVABLE`, `DAILY_QUOTA_EXCEEDED`, `MONTHLY_QUOTA_EXCEEDED` or
`CONTENT_INVALID`.

On failure, `ErrorClassifier` maps the error to an `ErrorCategory`
(TRANSIENT / PERMANENT / INVALID_INPUT / AUTH / CONFIG / QUOTA / UNKNOWN), and
the retry policy (`ExponentialBackoffPolicy`) decides:

- **Retry** → `markRetry(nextRetryAt = now + backoff)`; the delivery loop claims
  the record again once `next_retry_at` has passed
- **Fail** → `markFailed` (terminal; no retry) for PERMANENT / INVALID_INPUT /
  AUTH / CONFIG — none of them heals by retrying: fix the cause, then use the
  manual Retry action
- **DeadLetter** → `markDeadLetter` + archive a `dead_letter_message` row
  (`source = SendExhausted`) once the retry budget is spent

A failed SMTP send is coded with the server's reply when it has one:
`SmtpMailTransport` digs the reply code out of Spring's `MailSendException`
(its failed messages, then each cause chain) and records `SMTP_550`,
`SMTP_451`…; without one the code is `SMTP_` + the exception type
(`SMTP_MailAuthenticationException` for a failed login). `ErrorClassifier` reads
an SMTP reply by its class: 4xx TRANSIENT, 5xx PERMANENT, except 552
(message too large) INVALID_INPUT and 530 / 534 / 535 / 538 AUTH. Error codes
and messages are cut to their columns (100 / 1000 characters, ending in "…")
before a transition writes them, so an over-long provider message cannot fail
the write that records the failure; `dead_letter_message.last_error_msg` is
1000 characters wide and cut the same way.

Business code usually does not need to choose a mail server explicitly. Defaults
should be prepared by the platform or tenant admin.

### Mail Server Selection

Like SMS, **mail server selection is single-pick with no provider switching
after a send failure**. The selection chain at send time:

```text
SendMailDTO.serverConfigId          (1) explicit call-site override
  ↓ null
preferredServerConfigId              (2) template-level soft preference
  ↓ null
MailServerDispatcher.resolveSend()   (3) tenant default → platform default (relay)
  ↓ none found                           (PLATFORM scope: platform default only)
BusinessException

(1) and (2) are looked up in the scope's own table: a TENANT send pins one of the
tenant's servers, a PLATFORM send one of the platform's.
```

Once a config is chosen, that's it — there is no "primary failed, try
secondary" behaviour. SMTP failure goes through the normal retry policy
(retry against the same server with backoff), not server-switching.

#### What the fields mean

| Field | Used for | NOT used for |
|---|---|---|
| `isDefault` (send / receive server) | Marks the owner's default — the tenant's or the platform's. The standard write endpoints keep it unique per owner — saving a server as default demotes the previous one | Failover (only the first default is ever picked) |
| `sequence` (send server) | Tie-break among multiple `isDefault=true` rows (only reachable via batch/copy/init-script writes) + UI list order | Failover priority |
| `sequence` (receive server) | Cron polling order (all active servers polled each tick) + UI list order | Failover priority |
| `preferredServerConfigId` (template) | Per-template preferred SMTP (e.g. marketing→SendGrid, transactional→Postmark). Checked on every write: a tenant template pins one of its tenant's servers, a platform template one of the platform's | Hard binding — DTO can still override |

Send resolution failure is loud: when no default of the owners the scope allows
exists, `resolveSend()` logs an ERROR (with the tenant id) and rejects
the request with a `BusinessException` — an ops-actionable signal that the
deployment is missing its default server config. `resolveReceive()` only
rejects: a tenant without a receive server of its own is a valid setup, not a
deployment gap.

> Naming note: the field is called `sequence` (not `priority`) because the
> mail side uses the value for UI / default ordering, not a retry chain. The SMS
> side keeps `priority` because country routing and template bindings both use
> it as explicit provider-selection order.

#### Use cases for `preferredServerConfigId`

- **Marketing vs transactional split**: marketing templates → tracking-pixel
  SMTP (SendGrid), transactional → high-deliverability SMTP (Postmark)
- **From-domain alignment**: HR templates from `hr@company.com` via corporate
  Exchange, brand templates from `noreply@brand.com` via SendGrid
- **Compliance**: legal disclosure templates locked to internal SMTP
- **Multi-tenant white-label**: each tenant's welcome template points at their
  own configured SMTP

Soft preference (not hard binding) because callers occasionally need an
override path — e.g. ops cuts all outbound to the backup SMTP during a
provider outage by setting `SendMailDTO.serverConfigId` at the call site
without touching every template row.

### Sending Email

Inject the single application-facing `MessageService`:

```java
@Autowired
private MessageService messageService;

// Plain text
SendMailDTO plain = new SendMailDTO();
plain.setTo(List.of("alice@example.com"));
plain.setSubject("Hello");
plain.setTextBody("Welcome to Softa.");
Long recordId = messageService.sendMail(plain);

// Full control. Multiple `to` recipients share one MIME message and one record.
SendMailDTO dto = new SendMailDTO();
dto.setTo(List.of("a@x.com", "b@x.com"));
dto.setCc(List.of("manager@example.com"));
dto.setSubject("Offer Letter");
dto.setHtmlBody("<p>Dear Alice...</p>");
dto.setAttachments(List.of(attachment));
Long fullRecordId = messageService.sendMail(dto);
// IDs point at PENDING records; the delivery loop moves them on to SENT/FAILED.
// To check terminal status, query MailSendRecordService.getById(recordId).
```

> **All mail sends are asynchronous.** `sendMail / sendMailBatch` persist a
> `MailSendRecord (PENDING)` in the caller's transaction and return
> immediately; rendering and SMTP delivery happen in the delivery loop.
> There is deliberately no synchronous variant: an SMTP `250 OK` is **not** the
> same as "user has the email" — the user still waits seconds-to-minutes for the
> provider to deliver, so the ≤500ms poll is invisible, while a single async path
> avoids blocking HTTP threads. A missing server or template therefore shows on
> the record (FAILED, with the reason), not as an exception at the caller.

#### Address rules

Addresses are validated at **acceptance** (`MailAddresses`) — a bad address is
a synchronous 4xx naming the field and the offending entry, never an
asynchronous SMTP failure discovered later on the send record:

- `to` / `cc` / `bcc`: each list entry is exactly ONE strict RFC822 mailbox.
  Display-name form (`Alice <alice@x.com>`) is allowed; an embedded list in a
  single entry (`"a@x.com, b@y.com"`) is rejected — one entry, one mailbox.
- `replyTo`: one string carrying an RFC822 **address-list** — one or more
  mailboxes. Comma / semicolon / newline separators are all accepted and
  normalized to a comma list at acceptance; the record stores the normalized
  form, so retries replay it verbatim. Pure comma input is never rewritten,
  which keeps quoted display-names with embedded commas
  (`"Smith, John" <j@x.com>`) intact — that form cannot be mixed with
  semicolon/newline separators.

### Independent Batch

```java
SendMailDTO alice = new SendMailDTO();
alice.setTo(List.of("alice@example.com"));
alice.setTemplateCode("ORDER_CONFIRMATION");
alice.setTemplateVariables(Map.of("orderNo", "SO-1001", "name", "Alice"));

SendMailDTO bob = new SendMailDTO();
bob.setTo(List.of("bob@example.com"));
bob.setTemplateCode("ORDER_CONFIRMATION");
bob.setTemplateVariables(Map.of("orderNo", "SO-1002", "name", "Bob"));

List<Long> ids = messageService.sendMailBatch(List.of(alice, bob));
```

### Attachments

```java
FileInfo attachment = fileService.uploadFromStream(uploadRequest);
SendMailDTO mail = new SendMailDTO();
mail.setAttachments(List.of(attachment));
```

Upload bytes through `file-starter` first, then pass the resulting `FileInfo`.

### Email Templates

Use templates when business content should be reusable:

```java
@Autowired
private MessageService messageService;

Map<String, Object> vars = Map.of(
    "name", "Alice",
    "activationUrl", "https://app.example.com/activate/abc123"
);

SendMailDTO mail = new SendMailDTO();
mail.setTo(List.of("alice@example.com"));
mail.setTemplateCode("USER_WELCOME");
mail.setTemplateVariables(vars);
messageService.sendMail(mail);
```

#### Template example

```bash
POST /MailTemplate/createOne
{
  "code": "USER_WELCOME",
  "name": "User Welcome Email",
  "subject": "Welcome, {{ name }}!",
  "bodyHtml": "<h1>Welcome, {{ name }}</h1><p><a href='{{ activationUrl }}'>Activate</a></p>",
  "bodyMode": "HTML",
  "active": true
}
```

### Receiving Email

If the business needs inbound mail processing, inject `MailReceiveService`:

```java
@Autowired
private MailReceiveService mailReceiveService;

// The current tenant's receive servers
int fetched = mailReceiveService.fetchNewMails();
int fetchedByServer = mailReceiveService.fetchNewMails(serverConfigId);
mailReceiveService.markAsRead(recordId);

// The platform's receive servers
int platformFetched = mailReceiveService.fetchPlatformMails();
mailReceiveService.markPlatformAsRead(List.of(id1, id2));
```

Received mail belongs to the owner of the server it arrived through, and neither
side falls back to the other:

- `fetchNewMails()` (`POST /MailReceiveRecord/fetch`) reads the tenant's default
  receive server; a tenant with none fails with "No receiving mail server is
  configured for this tenant". `fetchNewMails(serverConfigId)`
  (`POST /MailReceiveRecord/fetchByServer`) finds only the tenant's own servers —
  a platform server's id is "not found".
- `fetchPlatformMails(...)` (`POST /PlatformMailReceiveRecord/fetch` /
  `fetchByServer`) reads the platform's receive servers into
  `PlatformMailReceiveRecord`.

Messages are deduplicated by `(server_config_id, message_id)` within the owner's
table, so repeated polling is safe. Attachments are uploaded under the receiving
record's own model. Bounce and read-receipt classification matches inbound mails
against the send log in a single batched `IN()` query, restricted to the records
the same owner's send servers carried (`findByMessageIds(ids, carriedBy)`); the
matched `MailSendRecord` is updated via CAS (see `markBounced` /
`markReadReceiptReceived`). How the original `Message-ID` is found is described in
*Inbound delivery status*.

### Scheduled Fetch

- Scheduled fetch is optional and requires `cron-starter`
- The current consumer listens to `mq.topics.cron-task.topic`
- When it receives a cron whose name starts with `mail-fetch`, it polls every
  active receive server — the platform's in the platform's context, every
  tenant's in that tenant's context
- Cadence is governed by a single global `mail-fetch` cron registered in
  `cron-starter`; per-inbox cadence is not supported in this module

### Email Status Reference

#### MailSendRecord

```text
Pending -> Sending -> Sent
                  -> Retry -> Sending -> Sent
                          -> DeadLetter
                  -> Failed
```

- `Pending` — record created, waiting for the delivery loop
- `Sending` — claimed by the delivery loop via CAS; preparation or SMTP send in flight
- `Sent` — SMTP server accepted the message
- `Retry` — transient failure; re-driven after `next_retry_at` elapses
- `Failed` — permanent SMTP reject, auth failure, or a send that could not be prepared (no server, template missing or broken, quota used up, empty content)
- `DeadLetter` — retry budget exhausted; ops intervention required

A record can transition `Sent → Failed` when an inbound bounce is correlated.

`serverOwner` (`Tenant` / `Platform`) records whose send server carried the mail;
with `serverConfigId` it names the server a retry replays.

#### MailReceiveRecord

```text
Unread -> Read -> Archived
               -> Deleted
```

## SMS

### Core Logic

#### Config resolution

SMS is a platform service: providers, country routing, templates and template
bindings exist once, for every tenant, in tables that are not multi-tenant.
Provider selection is by the recipient's country (see *SMS Provider Routing*),
then the enabled `isDefault` providers ordered by `priority`, else a
`BusinessException`. Provider configs are cached in Redis (5 min TTL) and evicted
after every committed write, from any endpoint (`ConfigCacheEvictor`).

A provider that sends a template registered with it (a binding's external
template id — Aliyun, Tencent Cloud) is sent the record's template variables,
not the rendered text: Aliyun fills them by name, Tencent by position. Positions
follow the order the softa template's content names the variables (`{{ code }}`
before `{{ expiryMinutes }}` makes `code` `{1}`); preparation fixes that order on
the record, so every retry sends the same.

#### Template resolution

SMS templates are resolved by `code` alone — there is one tier, and the send's
`MessageScope` changes nothing for SMS beyond being recorded: the record is filed
under, and draws on the quotas of, the tenant the send is made for (the
calling context's; the platform when there is none).

Template placeholders use the unified Softa syntax: `{{ variable }}`.

#### Delivery pipeline

Identical to mail. Each recipient becomes one `SmsSendRecord`; the state machine
is the same six-state CAS flow (`PENDING → SENDING → SENT / FAILED / RETRY /
DEAD_LETTER`). Provider routing happens when the record is prepared (its first
delivery attempt), and retry replays the same provider/template parameters
stored on the record. No provider for the recipient's country fails the record
with `CONFIG_NOT_RESOLVABLE`.

### Sending SMS

Use the same `MessageService` for SMS:

```java
@Autowired
private MessageService messageService;

// Plain text to a single recipient
SendSmsDTO dto = new SendSmsDTO();
dto.setPhoneNumber("+1234567890");
dto.setContent("Order #1234 has been shipped.");
Long id = messageService.sendSms(dto);

// Independent batch: one DTO and one send record per phone number.
SendSmsDTO first = new SendSmsDTO();
first.setPhoneNumber("+1111111111");
first.setContent("System maintenance tonight at 10pm.");
SendSmsDTO second = new SendSmsDTO();
second.setPhoneNumber("+2222222222");
second.setContent("System maintenance tonight at 10pm.");
List<Long> ids = messageService.sendSmsBatch(List.of(first, second));
// ids point at PENDING records; the delivery loop moves them on to SENT/FAILED.
```

> **All SMS sends are asynchronous** — same contract as Mail. `sendSms /
> sendSmsBatch` persist an `SmsSendRecord (PENDING)` in the caller's
> transaction and return immediately; the delivery loop renders, routes and
> performs the provider call.

### Send Modes

| Mode | Main fields | Description |
|---|---|---|
| Single | `sendSms(SendSmsDTO)` | One recipient and one record |
| Batch | `sendSmsBatch(List<SendSmsDTO>)` | 1..500 independent messages, atomic and ordered |
| Template | `templateCode` + `templateVariables` on each DTO | Render then send |

### Per-recipient template variables

```java
SendSmsDTO first = new SendSmsDTO();
first.setPhoneNumber("+111");
first.setTemplateCode("ORDER_STATUS");
first.setTemplateVariables(Map.of("orderId", "A001", "status", "Shipped"));

SendSmsDTO second = new SendSmsDTO();
second.setPhoneNumber("+222");
second.setTemplateCode("ORDER_STATUS");
second.setTemplateVariables(Map.of("orderId", "B002", "status", "Delivered"));

messageService.sendSmsBatch(List.of(first, second));
```

### SMS Templates

```java
Map<String, Object> vars = Map.of("code", "123456", "minutes", 5);

SendSmsDTO sms = new SendSmsDTO();
sms.setPhoneNumber("+1234567890");
sms.setTemplateCode("VERIFY_CODE");
sms.setTemplateVariables(vars);
messageService.sendSms(sms);
```

#### Template example

```bash
POST /SmsTemplate/createOne
{
  "code": "VERIFY_CODE",
  "name": "Verification Code",
  "content": "Your verification code is {{ code }}. Valid for {{ minutes }} minutes.",
  "active": true
}
```

### SMS Provider Routing (by country)

`SmsProviderDispatcher` picks the provider(s) for an outbound SMS based on
the recipient's country, parsed from the E.164 phone number via
[libphonenumber](https://github.com/google/libphonenumber). Resolution is
**two-tier, strict, no implicit cross-tier fallback**:

```text
parseRegion(+8613800138000) -> "CN"

  Tier 1: PRECISE      Tier 2: CATCHALL              FAIL
  ┌──────────────┐     ┌──────────────────────┐     ┌────────────────────┐
  │ Enabled rows │     │ SmsProviderConfig    │     │ BusinessException  │
  │ in           │ ──▶ │ where isDefault=true │ ──▶ │ "No provider for X"│
  │ sms_provider │     │ (ordered by priority)│     │                    │
  │ _region      │     │                      │     │                    │
  │ matching CN  │     │                      │     │                    │
  └──────────────┘     └──────────────────────┘     └────────────────────┘
        │                       │                            │
        ▼                       ▼                            ▼
  Use this ordered       Use this ordered               Send fails;
  candidate list         candidate list                 fix config to recover
```

#### Configuration model

| Table | Purpose | Per-row data |
|---|---|---|
| `sms_provider_config` | Provider accounts and credentials | API key, sender number, `isDefault`, `priority` |
| `sms_provider_region` | Per-country routing rules | `region_code` (ISO 3166-1 alpha-2 string), `dial_code` (denormalized), `priority` |

Two tables intentionally — one provider account commonly serves N countries
(Twilio US/CA/MX/UK/AU/…). 1-to-N relationship is normalised into a routing
table; per-region priority is a column on the routing row, not on the config.

`region_code` is the ISO 3166-1 alpha-2 country code (`CN`, `TW`, `US`, …),
validated at write time against `country_region.code` in
`reference-data-starter`. The reference table holds all 249 ISO 3166-1
territories with English name, alpha-3 code, E.164 dial code, default
currency, continent, EEA flag, and subdivisions flag. **`reference-data-starter`
must be on the classpath** for SMS provider routing to function — `message-starter`
depends on it as a hard dependency.

`dial_code` is a framework-maintained **stored cascade** of
`regionCode.dialCode` (`CountryRegion` is code-as-id, so the region FK stores
the alpha-2 code itself). It lets admin list views render "CN (+86) → Aliyun"
without joining `country_region`. Operators must not edit `dial_code`
directly — the framework derives it from `region_code`.

#### Catchall semantics

There is **no magic** `region_code='*'` row. Catchall is expressed by
`SmsProviderConfig.isDefault=true` — keeps `region_code` strictly an ISO
3166 alpha-2 value with no special carve-outs in the schema.

#### Resolution rules

1. **Precise match wins fully** — if any `sms_provider_region` row matches
   the recipient's country, only that tier's providers are used. The
   dispatcher does **NOT** merge in the catchall tier.
2. **Catchall** is consulted only when **no precise row** matches, never as
   an in-line fallback for a partial precise match.
3. **Explicit over implicit** — to make a catchall provider eligible for a
   configured country too, add it explicitly as another
   `sms_provider_region` row for that country.

This rule keeps misrouting deterministic: a country you've explicitly
configured cannot accidentally fall through to a wrong provider (e.g. TW
traffic should never route through a mainland-CN-only line).

#### Failure mode

If neither precise nor catchall yields a provider, the dispatcher throws
`BusinessException` with the unresolved region in the message. The send
**fails fast** — there is no "any-enabled-provider" implicit fallback.
Operators recover by either adding the missing `sms_provider_region` row or
marking at least one provider `isDefault=true`.

#### Example configuration

| `sms_provider_config` | id | name | provider_type | is_default | priority |
|---|---|---|---|---|---|
|  | 1 | Aliyun-China  | ALIYUN  | false | 10 |
|  | 2 | Tencent-China | TENCENT | false | 20 |
|  | 3 | Twilio-Global | TWILIO  | true  | 1  |
|  | 4 | Vonage-Backup | VONAGE  | true  | 2  |

| `sms_provider_region` | provider_config_id | region_code | priority |
|---|---|---|---|
|  | 1 | CN | 10 |
|  | 2 | CN | 20 |
|  | 3 | TW | 10 |

Dispatch behaviour:

| Recipient | Resolved provider list | Notes |
|---|---|---|
| `+8613800138000` (CN) | `[Aliyun, Tencent]` | Precise match; selection uses ordered CN candidates |
| `+886912345678` (TW)  | `[Twilio]`           | Precise match; **Vonage is NOT added** as fallback |
| `+33123456789` (FR)   | `[Twilio, Vonage]`   | No precise FR; falls to isDefault catchall |
| `+44...` if no GB row and no defaults | throws `BusinessException` | Operator must configure |

#### Tenant scoping

None: routing is the platform's, one set of rows for every tenant.

#### Template-level provider bindings

`sms_provider_region` (above) decides **which provider accounts are eligible
to send to a country**. `SmsTemplateProviderBinding` (separate table) layers
on top of that to express **per-(template, provider) details**: provider-side
external template ID, signName, optional `region_code`, and binding-level
priority. The dispatcher runs first to filter eligible providers by country;
`SmsRoutingPlanner` then intersects those providers with template bindings and
persists one selected provider plus the external IDs/signNames. The two concerns are
deliberately split — country eligibility on the provider, template-specific
overrides on the binding.

### SMS Provider Selection and Retry

When a template is bound to multiple eligible SMS providers, planning at the
first delivery attempt selects one provider in `priority` order (lower =
preferred):

```text
country route candidates ∩ template bindings -> selected provider
```

- `SmsSendRecord` stores the selected provider's `provider_config_id`,
  `provider_type`, `external_template_id`, and `sign_name`.
- If the provider call fails, `ExponentialBackoffPolicy.decide(...)` determines
  the next step (RETRY / FAILED / DEAD_LETTER) — same contract as mail.
- During retry, the record replays the same provider and template parameters.

### SMS Status Reference

#### SmsSendRecord

```text
Pending -> Sending -> Sent
                  -> Retry -> Sending -> Sent
                          -> DeadLetter
                  -> Failed
```

Semantics match `MailSendRecord`. The `deliveryStatus` /
`deliveryStatusUpdatedAt` columns hold the provider-reported delivery outcome;
they stay `UNKNOWN` unless your application feeds provider delivery receipts
(DLR) back into the record.

## Inbox

### Core Logic

- Inbox is used for in-app communication rather than external channel delivery
- `InboxNotification` is read-only information sent to users
- `flow-starter` pushes notifications during approval or review steps

### Inbox Notifications

Self-service endpoints for the signed-in user (always scoped to
`ContextHolder.getContext().getUserId()`):

| Method | Path | Purpose |
|---|---|---|
| GET | `/InboxNotification/myCountUnread` | Unread badge count |
| GET | `/InboxNotification/myRecent?limit=` | Recent list (max 50) |
| POST | `/InboxNotification/myMarkAsRead?id=` | Mark one owned row read |
| POST | `/InboxNotification/myMarkAllAsRead` | Mark all owned rows read |
| POST | `/InboxNotification/myMarkAsReadBySource?sourceModel=&sourceId=` | Mark the owned unread rows about one object read — an approval its recipient opened |
| POST | `/InboxNotification/mySearch` | One page of the owned rows for a table: body `{ filters, orders, pageNumber, pageSize }`, newest first by default |
| POST | `/InboxNotification/myMarkAsReadByIds` | Mark the owned unread rows among the ids in the body (a JSON array) read; another user's ids are passed over. Returns how many were marked |

`mySearch` serves a metadata-driven table over `InboxNotification` — a notification center with search, column filters, sort and paging. The request's `filters` are ANDed onto the caller's own rows, so a filter naming another recipient finds nothing; `searchName` matches `title` and `content` (the model's `searchName`). Row scope is waived on every `my*` endpoint, so `filters` and `orders` may name only fields the model stores: a dotted path (`createdId.…`) or an unknown name is refused with 400 rather than answered from rows the caller could not read. A sort of the caller's ends on `id` in the direction of its first, so pages never overlap. The index `(recipientId, createdTime)` serves the default order.

Grant the FE `my-inbox` custom permission (or whitelist these paths under
`permission.authenticated-bypass-patterns`) so every employee can use the
header bell without Message-module admin rights.


Use `MessageService` to submit read-only notifications. Query/read operations
remain on `InboxNotificationService` because they are not message submission:

```java
@Autowired
private MessageService messageService;
@Autowired
private InboxNotificationService inboxNotificationService;

SendInboxDTO notification = new SendInboxDTO();
notification.setRecipientId(userId);
notification.setTitle("Order shipped");
notification.setContent("Your order #1234 has been dispatched.");
Long notificationId = messageService.sendInbox(notification);

int unread = inboxNotificationService.countUnread(userId);
inboxNotificationService.markAsRead(notificationId);
inboxNotificationService.markAllAsRead(userId);
```

A recipient, a title and content are required. The content has no width (`TEXT`): it is free text — an approval's
comment, an automation's template, the error that stopped a step. A title longer than its column (200) is cut to fit,
ending in "…", rather than refused: senders tell users past the action that sends, where a refusal is only logged
and the message was lost.

## Operations

### Metrics

When Micrometer is on the classpath, `MessageMetrics` emits two counter
families:

| Name | Tags | When incremented |
|---|---|---|
| `softa.message.sent` | `channel` (mail/sms), `provider` | Provider call succeeded |
| `softa.message.failed` | `channel`, `provider`, `outcome` (retry/failed/dead_letter) | A send attempt failed, preparation included |

`softa.message.failed{outcome=dead_letter}` is emitted by `SendFailureHandler`
when a send record transitions to DEAD_LETTER.

### Delivery timing

Delivery is asynchronous, so its latency is read from persisted timestamps on the
send record, not in-process timers:

| Moment | Column |
|---|---|
| Accepted | `created_time` |
| Retry scheduled for | `next_retry_at` (latest retry) |
| Attempt started (delivery loop claim) | `attempt_started_at` — latest attempt |
| Provider accepted | `sent_at` |
| Provider call length | `send_duration_ms` — the successful attempt |

For a record sent on its first attempt: queue wait = `attempt_started_at` −
`created_time` (at most the poll interval, unless every worker was busy);
provider time = `send_duration_ms`; end to end = `sent_at` − `created_time`. A
failed attempt ends at the record's `updated_time`, so its length is
`updated_time` − `attempt_started_at` until the next claim overwrites it.
`created_time` is taken inside the accepting transaction, so a long caller
transaction shows up as queue wait. MySQL `DATETIME` columns hold whole seconds
(PostgreSQL `TIMESTAMP` keeps microseconds); `send_duration_ms` is exact to the
millisecond on both.

### Inbound delivery status

Mail bounce and read-receipt status is derived from **inbound mail** on the
IMAP receive path — DSN report emails (`DsnRule` / `MailerDaemonRule` →
`BounceReceiptLinker` → `markBounced`) and MDN emails (`ReadReceiptRule`). No
inbound HTTP callback is provided; for provider-pushed delivery events (SMS
DLR, ESP mail events) add a controller in your application that calls the
record services' CAS transitions.

A report reaches its send record by the original mail's `Message-ID`, which
`MailClassificationSupport.extractOriginalMessageId` reads for all three paths
(standard DSN, read receipt, non-standard bounce), first found wins:

1. the report's own `In-Reply-To`, then the first entry of `References`;
2. a read receipt's `Original-Message-ID` field, in its
   `message/disposition-notification` part (RFC 8098);
3. the returned original's `Message-ID`, in a `text/rfc822-headers` or
   `message/rfc822` part — the third part of a DSN (RFC 3464 asks a DSN for
   neither header of step 1), or the original a non-standard bounce attaches.

The id is taken in the form the send record stores it — JavaMail's
`<id@host>`, angle brackets kept, case untouched — since `findByMessageIds`
matches it exactly. Steps 2 and 3 read the direct parts of the report and skip a
part that cannot be read. A bounce whose original is in none of these places —
or a relay that rewrote the `Message-ID` — is kept as received mail but reaches no
send record.

Only a DSN that reports a failure (`Action: failed` for a recipient) is linked.
A `delayed` DSN warns that the MTA is still retrying, and `delivered` /
`relayed` / `expanded` report success; linking would mark the send record
bounced and `Failed`, so they are kept as received mail only. The DSN fields are
read from the `message/delivery-status` part's text: Jakarta Mail has no content
handler for that type, so its `getContent()` is a raw stream.

### Rate limits

`MailSendServerConfig` and `SmsProviderConfig` carry two quota columns:

- `daily_send_limit` — cumulative sends per day
- `rate_limit_per_minute` — sends per minute (smooths bursts)

Either can be left null/zero to disable that window. Counters live in Redis
(`rl:{channel}:{daily|min}:{tenantId}:{configId}:{window}`), so multi-instance
deployments share one budget. A quota breach surfaces as a provider-side
`QUOTA_EXCEEDED` error, classified as `ErrorCategory.QUOTA` — the retry policy
waits at least 5 minutes before the next attempt so we don't hammer the
provider.

### Zombie record sweeper

`ZombieRecordSweeper` runs every minute. Records stuck in `SENDING` whose
`updated_time` is older than five minutes — far longer than any send, whose
connect and read timeouts are seconds — are versionLock-transitioned back to
`RETRY` with `next_retry_at = now`,
which makes them due for the delivery loop again. This covers JVM crashes
between claiming a record and finishing the provider call.

The window holds only while every send finishes well inside it, so the sweeper
refuses to start when a channel's `connection-timeout` plus `read-timeout` is
more than half of it (2m30s), or either is zero — no timeout to Jakarta Mail: a
send that is only slow would otherwise be revived while still in flight and sent
twice. The window itself stays a constant.

Each revival counts as a retry and is held to the same budget as a failed send:
a stuck record whose `retry_count` has reached the retry policy's five attempts
is moved to `DEAD_LETTER` (error code
`ZOMBIE_EXHAUSTED`, archived to `dead_letter_message`) instead of being revived.
An attempt that never finishes — a crash or hang on every try — never reaches the
failure handler, so without this check it would be revived every stale window,
forever.

It also covers a send the provider accepted but whose `SENT` write failed (a
database error right after the call). That is not a send failure, so it is never
handed to the retry policy: the record stays `SENDING`, the delivery processor
logs it at ERROR with the record id and the provider message id, and the sweep
re-sends it once after the stale window — the price of at-least-once delivery —
or dead-letters it if its retry budget is already spent.

It runs wherever message-starter runs, alongside the delivery loop.

### Sensitive field encryption

The credential columns of the config tables — the mail servers' `password`
(tenant and platform tables) and `sms_provider_config.api_key` / `api_secret` —
are declared `encrypted = true` and `maskingType = ALL`: AES-encrypted at rest,
masked in API reads, kept when an update leaves them out. A value stored as
plaintext before the flag was declared is still read as it is, with a WARN
naming the rows; saving the secret again encrypts it.

## Extension Points

### Mail transport

Mail sending is SMTP-only. `MailSendServerConfig` is the complete outgoing
server configuration. `SmtpMailTransport` keeps authenticated connections open
between sends, one small pool per server config, so a run of mails through one
server pays the TCP connect, TLS handshake and AUTH once instead of per message.
An idle connection is probed with `NOOP` before reuse. A connection that saw a
failure, carried 100 messages, or sat idle past 30 seconds is closed instead.
The idle cut-off stays below that of any NAT or load balancer in front of the
SMTP server: a connection dropped silently would only answer the probe with a
read timeout.

Each pool is keyed by the settings its connections were opened with, so an
edited config (new host, rotated password) opens fresh connections on its next
send, and config changes still only need the Redis config cache evicted
(automatic after every committed write, see `ConfigCacheEvictor`). Connectivity tests always open a new
connection.

A server that does not take a second message on one connection has
`connectionReuseEnabled` turned off on its config (on by default): every send
through it opens and closes its own connection, and the pool it had is closed
on its next send.

### Mail classification rules

`MailClassifier` is a chain-of-responsibility over `MailClassificationRule`
beans. The four stock rules (read-receipt → DSN → mailer-daemon → keyword) run
in `@Order` sequence; the first rule that returns a classification wins. Add
provider-specific detection — e.g. legacy Exchange NDRs, Chinese ISP bounce
shapes — with a new rule:

```java
@Component
@Order(25)  // between DsnRule (20) and MailerDaemonRule (30)
public class ExchangeNdrRule implements MailClassificationRule {
    @Override
    public Optional<MailClassification> match(MimeMessage message) throws Exception {
        // ... return MailClassification.bounce(info) if it matches
        return Optional.empty();
    }
}
```

### Dead letter store

Dead letters from both layers converge into a single `dead_letter_message`
table for triage, discriminated by a `source` column:

- `SendExhausted` — a mail / SMS send record exhausted its provider retry
  budget; archived by `SendFailureHandler` (record id in `event_id`, failure
  detail in the JSON `payload`).
- `BrokerPoison` — a Pulsar consumer could not process a message after the
  broker's max redeliveries; the raw envelope is routed to the DLQ topic and
  archived by `DeadLetterConsumer`. Opt a listener in with
  `@PulsarListener(deadLetterPolicy = "commonDlqPolicy")` and set
  `softa.message.dlq.topic`.

Triage the rows via `DeadLetterMessageController` (status `Pending` →
`Resolved` / `Discarded`). For custom alerting (Slack, PagerDuty), consume the
DLQ topic or watch the table — there is no in-process listener SPI.

### Retry policy

Failed sends are retried by `ExponentialBackoffPolicy` — exponential back-off of
30 s, 60 s, 120 s, 240 s and 480 s, each ±50% jitter. The error category from
`ErrorClassifier` decides the disposition: TRANSIENT / QUOTA / UNKNOWN retry
(QUOTA waits at least 5 minutes) until five retries are used up and the record
is dead-lettered, about a quarter of an hour after the first failure; PERMANENT / INVALID_INPUT / AUTH / CONFIG fail immediately
without retry. `RetryDecision` is a sealed type (`Retry` / `Fail` / `DeadLetter`) so the
failure handler's `switch` stays exhaustive.
