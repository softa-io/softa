# Message Starter

Provides unified messaging capabilities for Softa applications:

- **Email**: send emails, receive emails, and render email templates
- **SMS**: send SMS messages, batch send, render templates, and retry on failure
- **Inbox**: push in-app notifications to users

Delivery reliability is built on a **transactional outbox** + **optimistic-lock CAS**
state machine, so broker failures, duplicate deliveries, and in-flight crashes are
handled without message loss or double-sends.

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

Apply the following DDL under `src/main/resources/sql/`:

- `message-starter.sql` — email + inbox tables
- `message-starter-sms.sql` — SMS tables
- `message-starter-outbox.sql` — transactional outbox (shared by mail + SMS)
- `message-starter-dlq.sql` — unified dead-letter store (`dead_letter_message`)

Uses the framework ORM/versionLock path for runtime writes, so outbox publishing
does not depend on database-specific row-lock SQL.

### Hard dependencies

`message-starter` deliberately treats Redis and the relational database as
**hard dependencies**. There is no fail-open / local-fallback path for them —
if either is unavailable the operation that depends on it surfaces an
exception to the caller. This keeps the runtime simple and matches how the
rest of the Softa stack already behaves (cache, distributed lock, session,
etc.). The trade-off and the operational expectations:

| Dependency | Used by | Failure behaviour | Operational expectation |
|---|---|---|---|
| Database | All paths (records, outbox, framework versionLock) | Operation throws; caller sees 5xx | HA-Database (replicated MySQL / managed PG); migrations applied. |
| Redis | `RateLimiter` (per-config delivery rate windows), `MailConfigCache` | Operation throws; caller sees 5xx | Sentinel or Cluster setup. K8s `readinessProbe` should include `/actuator/health/redis` so the load balancer routes traffic away while Redis is unreachable. |
| Pulsar broker | `OutboxPublisher` (publish), consumers (subscribe) | Outbox row stays `NEW`; publisher retries with exponential back-off; eventually marks `DEAD` after `MAX_PUBLISH_ATTEMPTS=10`. | HA cluster. Failure does not block business writes — outbox absorbs the gap. |
| SMTP / SMS provider | Outbound send | Per-record fails; classified by `ErrorClassifier`; retried with exponential back-off (`ExponentialBackoffPolicy`). | Configure provider-side rate limits below provider's quota. |

**Why no in-process fallback for Redis?** A local Guava limiter would silently
let one node burst past the cross-node quota during Redis outages, which on a
long enough outage can blow through provider day-quotas and cost real money
(Twilio / Aliyun / SES). It's safer to fail closed at the load-balancer level
via the readiness probe than to silently fan out under partial failure.

### Multi-tenancy

All messaging business tables (`mail_*`, `sms_*`, `inbox_notification`) are
`multiTenant` models: when the platform's `system.enable-multi-tenancy` is on,
reads are isolated to the caller's tenant and writes are auto-stamped by the
ORM. `tenant_id = -1` rows (`BaseConstant.PLATFORM_TENANT_ID`) form the
**platform tier** — owned by the platform operator and invisible to tenants.
The two tiers are fully separate namespaces; there is no overlay:

- **Templates are seeded per tenant, not shared.** Each tenant receives its
  template rows at provisioning from the APPLICATION's tenant seed files (the
  tenant files of its `seed-manifest.yml`, run by seed-starter when the tenant
  is set up — e.g. by hcm's `TenantSeedListener`). From then on the tenant
  owns and edits its rows freely. A tenant runs a seed file once, so a new
  template reaches existing tenants as a new tenant file the platform admin
  runs — or, when the application gives its template file `mode: append` in
  the manifest, straight from that file on the admin's next run, created only
  where a tenant lacks it. Either way, a change to a template they already
  have goes out as a data migration (the seed file is edited too, for tenants
  created after). The
  platform tier holds only the templates PLATFORM-scoped sends render
  (billing, security); a code needed on both tiers (e.g. a public
  password-reset fallback) appears in both seed sets. Send-time resolution is
  tier-pure: `scope = TENANT` reads the current scope's own rows only,
  `scope = PLATFORM` reads the platform tier only — no fallback in either
  direction on the template axis.
- **Server/provider configs are invisible to tenants.** Tenants see and pin
  only their own configs. The platform tier is reached in exactly one way:
  the dispatchers' silent fallback (`@CrossTenant`) when a tenant has none —
  mail send/receive fall back to the platform default; SMS routing falls back
  **per country** (a country the tenant has not routed uses the platform's
  rows for that country; tiers never interleave within one country), and the
  SMS catchall default is tenant-first-else-platform the same way.
- Per-send tier policy: `SendMailDTO.scope` / `SendSmsDTO.scope` /
  `MailRequestMessage.scope` (`MessageScope.TENANT` default, `PLATFORM` = the
  platform tier for template, server AND quota bucket — for billing/security/
  compliance messages). `MailRequestMessage.tenantId` lets the MQ consumer
  restore the tenant context, so a TENANT-scoped render reaches the tenant's
  own template and the send record lands in the tenant's books.
- Platform rows are structurally un-writable from a tenant scope (payload
  guard, insert stamping, and the tenant-filtered pre-read on update/delete);
  the mail write endpoints additionally turn that silent no-op into an
  explanatory `BusinessException`.
- Background jobs are cross-tenant scans that execute per-record in the owning
  tenant's context: the scheduled mail fetch runs each receive config inside
  its config's tenant, and the zombie sweeper revives each stuck record inside
  its record's tenant.
- The transactional outbox and the dead-letter store are shared infrastructure
  tables; tenant identity travels inside the message payload
  (`recordId / tenantId / traceId`) and is restored by the consumer.

With multi-tenancy disabled, no filtering or stamping occurs and everything
behaves single-tenant.

### Enable / disable (framework active control)

The seven config/template models (`MailTemplate`, `MailSendServerConfig`,
`MailReceiveServerConfig`, `SmsTemplate`, `SmsProviderConfig`,
`SmsProviderRegion`, `SmsTemplateProviderBinding`) declare
`@Model(activeControl = true)` with the framework's `active` field — there is
no hand-rolled `isEnabled` switch and no service hand-writes an
`active = true` condition: `WhereBuilder` appends it to every FlexQuery read,
so resolution, dispatch and list surfaces filter consistently and a new query
cannot forget it. Disabling retires a row from every read WITHOUT deleting it,
which is what rows referenced by send records need. Consequences worth
knowing:

- A disabled row leaves the default list view, so each of the seven admin
  pages is a two-tab MultiView — **Active** (framework default) and **All**
  (`active IN (true, false)`, which names the field and therefore suppresses
  the automatic condition). Without that second tab, disabling a row would
  make it vanish from its own admin page. The column-header filter on `active`
  works the same way for ad-hoc queries.
- Reads that must reach disabled rows use `FilterControl.bypassActiveControl()`
  and say why: id-addressed replay (`findVisibleById` — an accepted record
  resolves the config it was accepted with, so disabling a config never turns
  in-flight retries into `CONFIG_NOT_RESOLVABLE`), authoring tooling
  (`resolveAny` / `findPlatformByCodeAny` — preview and variable extraction
  work before a template is activated), the platform-row write probe
  (`findPlatformById` — a disabled platform row must still produce the
  explanatory rejection), and default demotion (`demoteOtherDefaults` — a
  skipped disabled row would resurface as a second default when re-activated).
  Plain `getById` needs no bypass: id lookups never carry the filter.
- The SMS dispatcher's by-id provider read comes from the config cache, so its
  disabled-route check stays an explicit in-memory guard.

### Monthly send quotas

`TenantMessageQuota` (deliberately NOT `multiTenant` — a platform-owned
registry ABOUT tenants, writable only from the platform scope; the service
and the shadowed write endpoints both enforce `assertPlatformScope`) sets
per-tenant monthly ceilings for accepted mail/SMS sends. Enforcement is at
**acceptance** time in `MonthlyQuotaGuard`: an over-quota send is rejected
synchronously — a commercial ceiling, not rate limiting — and delivery
retries never touch the count. The bucket follows the send's scope:
`PLATFORM` sends draw on the platform's own `tenantId = -1` row (set it very
large; it exists to cap runaway or malicious mass sending). Missing rows fall
back to `softa.message.quota.mail-monthly-default` / `sms-monthly-default`
(null = unlimited; the ledger still advances for reporting).

The counters live in the **database**: one `TenantMessageUsage` row per
bucket per calendar month (server default zone), check-and-incremented via
the ORM's optimistic-lock CAS — the same `versionLock` pattern as the
delivery state machine — with each attempt in its own `REQUIRES_NEW`
transaction (a lost race retries on a fresh read and can never poison an
ambient batch transaction; consequence: a rolled-back accept keeps its
increment — a rare, reconcilable over-count). There is no reset job — a new
month simply starts a new row — and rows are never expired, so per-tenant
consumption history is a plain model query, with the ceiling in force
snapshotted onto each row. `GET /TenantMessageQuota/usage?tenantId=` serves
one bucket's usage vs currently-resolved limits (a tenant session may read
only its own bucket). The per-config `dailySendLimit` / `rateLimitPerMinute`
windows are a different axis (delivery-time infrastructure protection) and
stay unchanged.

### Async delivery (the only delivery model)

Every `MessageService.sendMail(...)` / `sendSms(...)` call follows the same
path regardless of broker configuration:

1. A `MailSendRecord` / `SmsSendRecord` and an `OutboxEntry` are written **in one
   DB transaction** (`status = PENDING`, `version = 0`). The method returns the
   record id(s) immediately — callers do not block on the SMTP/SMS round-trip.
2. The scheduled `OutboxPublisher` (500 ms poll) claims `NEW` rows as
   `PUBLISHING` through framework `versionLock`, publishes them to the
   corresponding topic, and flips the outbox row to `PUBLISHED`.
3. A `@PulsarListener` consumer reads the message (carrying only `recordId` /
   `tenantId` / `traceId`), then drives the channel's `DeliveryProcessor`, which CAS-transitions
   `PENDING|RETRY → SENDING` before invoking the provider.

If no broker topic is configured, outbox rows stay in `NEW` state and are retried
by the publisher on every poll — nothing is lost; sends just queue up until an
operator supplies a topic. Spring `@Async` is **not** used.

The mail-send and sms-send subscriptions are **Shared**: every instance consumes,
each with `softa.message.{mail,sms}.send-concurrency` consumers (default 1), so
one slow SMTP or provider call no longer holds up every message behind it.
Parallel consumers cannot send a record twice: two deliveries of the same record
race for the CAS claim in step 3, and only one wins. Outbox messages are published
unbatched, because a Shared subscription hands a whole broker entry to one
consumer and a batched burst would land on a single consumer. The mail-request and
sms-request subscriptions (templated mail / SMS requested over MQ, accepted by
`MailRequestConsumer` / `SmsRequestConsumer`) are Shared too, with one consumer per
instance — accepting a request is quick. A
request the starter rejects — unknown template, exhausted quota, invalid address,
no SMTP server; any `BusinessException` — is logged at ERROR and acknowledged:
it never became a send record, and a redelivery could only be rejected again.
Infrastructure failures still propagate and are redelivered.

> **Upgrading from an Exclusive subscription** (message-starter before this
> change): Pulsar refuses a Shared consumer while an Exclusive one is still
> attached to the same subscription, and the listener's default startup policy
> then fails the boot. Release this change stop-then-start — let the old
> instances disconnect before the new ones subscribe — rather than rolling.
> Later releases can roll normally.

### Broker topics

Only the channel topics you actually use need to be declared. Initial delivery
and delayed retries share the same topic; retry timing is carried by the
transactional outbox's `next_attempt_at` rather than encoded as a separate
broker route. Send dead-lettering is a terminal record state (`DEAD_LETTER`)
plus a row archived into the unified `dead_letter_message` store (see *Dead
letter store* below), not a separate queue.

```yml
mq:
  topics:
    mail-send:
      topic: dev_demo_mail_send
      sub: dev_demo_mail_send_sub
    sms-send:
      topic: dev_demo_sms_send
      sub: dev_demo_sms_send_sub
    cron-task:
      topic: dev_demo_cron_task
      mail-fetch-sub: dev_demo_cron_task_mail_fetch_sub
```

### Message-starter properties

Bound under `softa.message` from `MessageProperties`, `RetryProperties`, and DLQ `@Value` keys:

```yml
softa:
  message:
    outbox:
      enabled: true           # default true; disable on read-only replicas
      poll-interval-ms: 500
    zombie:
      enabled: true           # default true
      stale-seconds: 300      # stale SENDING/PUBLISHING claims are revived
      cron: "0 * * * * *"     # every minute
    retry:
      default-max-attempts: 5
      exponential:
        base-seconds: 30
        max-seconds: 3600
        multiplier: 2.0
        jitter: 0.5                 # ±50% randomisation
        quota-floor-seconds: 300    # QUOTA errors wait at least 5 min
    dlq:
      topic: dev_demo_message_dlq   # unset = broker-poison archiving disabled
      max-redeliver: 5              # broker nacks before dead-lettering
      alert:
        recipients: ops@example.com # comma-separated; empty = no alert mail
    mail:
      debug: false                  # Jakarta Mail protocol debug — never enable in prod (leaks AUTH)
      send-concurrency: 1           # parallel mail-send consumers per instance (each holds an SMTP connection)
      fetch:
        batch-limit: 100            # max messages per cron tick per (config, folder)
        lease-timeout: 1h           # abandoned IMAP watermark lease takeover
        max-message-size: 100MB     # RFC822 size cap; oversize → envelope-only + BodyTooLarge
        max-attachment-size: 20MB   # per-part cap; oversize parts skipped
        archive-eml: false          # opt-in raw EML archive via FileService
        max-mime-depth: 10          # MIME zip-bomb guard
        max-mime-parts: 100         # attachment-storm guard
      transport:
        connection-timeout: 5s      # SMTP/IMAP/POP3 connect timeout
        read-timeout: 30s           # SMTP/IMAP/POP3 read timeout
      smtp-pool:
        enabled: true               # reuse authenticated SMTP connections across sends
        idle-timeout: 30s           # idle connections older than this are closed, not reused
        max-messages-per-connection: 100
    sms:
      send-concurrency: 1           # parallel sms-send consumers per instance
      transport:
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
1. Current tenant default mail server
2. Platform default mail server (`tenant_id = -1`) — the silent fallback;
   platform configs are invisible on tenant management surfaces
3. BusinessException if nothing is available
```

If multiple records are marked as default, the one with the smallest `sequence`
is used. Config objects are cached in Redis for 5 minutes; updating a config
via `MailSendServerConfigService.updateOne` / `deleteById` evicts automatically.

A send declaring `scope = PLATFORM` skips step 1 and resolves the
platform default only (cached under the platform key, so the tenant's own
default cache entry is neither read nor poisoned) — platform mail must not
route through tenant-controlled SMTP.

#### Template resolution

Email templates are resolved by `code` within the tier the send's scope
names — the tiers are separate namespaces with no fallback:

```text
scope = TENANT (default) -> the current scope's own template (code + enabled)
                            (tenants receive their copies at provisioning)
scope = PLATFORM         -> the platform-tier template (tenant_id = -1)
either tier missing      -> BusinessException (loud — no cross-tier rescue)
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
  full production pipeline (render → record → outbox → SMTP): what was
  previewed is what goes out.

#### Delivery pipeline

Every accepted send produces exactly one
`MailSendRecord`. State transitions go through CAS helpers so duplicate broker
deliveries self-reject without a dedupe table:

```text
PENDING → SENDING → SENT
               ↓
               RETRY → SENDING → SENT
                   ↓
                   DEAD_LETTER (retries exhausted)
               FAILED (permanent provider reject: bad recipient, malformed input)
               DEAD_LETTER (non-retryable, non-permanent: auth failure or
                            unresolvable config — first failure, no retry burn)
```

On failure, `ErrorClassifier` maps the provider error to an `ErrorCategory`
(TRANSIENT / PERMANENT / INVALID_INPUT / AUTH / QUOTA / UNKNOWN), and the
retry policy (`ExponentialBackoffPolicy`) decides:

- **Retry** → `markRetry(nextRetryAt = now + backoff)` + enqueue a delayed
  outbox row on `mail-send` so the same delivery consumer re-drives it
- **Fail** → `markFailed` (terminal; no retry; permanent provider reject)
- **DeadLetter** → `markDeadLetter` + archive a `dead_letter_message` row
  (`source = SendExhausted`) — reached by exhausting the retry budget **or
  immediately** on a non-retryable, non-permanent failure (AUTH class). A
  config-resolution failure is stamped with the service-layer marker code
  `CONFIG_NOT_RESOLVABLE` and takes the immediate route: a deleted or broken
  config never heals by retrying — fix the config, then requeue via the
  manual Retry action.

Business code usually does not need to choose a mail server explicitly. Defaults
should be prepared by the platform or tenant admin.

### Mail Server Selection

Like SMS, **mail server selection is single-pick with no provider switching
after a send failure**. The selection chain at send time:

```text
SendMailDTO.serverConfigId          (1) explicit call-site override
  ↓ null
MailTemplate.preferredServerConfigId (2) template-level soft preference
  ↓ null
MailServerDispatcher.resolveSend()   (3) tenant default → platform default
  ↓ none found                           (PLATFORM scope: platform default only)
BusinessException
```

Once a config is chosen, that's it — there is no "primary failed, try
secondary" behaviour. SMTP failure goes through the normal retry policy
(retry against the same server with backoff), not server-switching.

#### What the fields mean

| Field | Used for | NOT used for |
|---|---|---|
| `MailSendServerConfig.isDefault` | Marks the tenant/platform default. The standard write endpoints keep it unique per tenant scope — saving a config as default demotes the previous one | Failover (only the first default is ever picked) |
| `MailSendServerConfig.sequence` | Tie-break among multiple `isDefault=true` rows (only reachable via batch/copy/init-script writes) + UI list order | Failover priority |
| `MailReceiveServerConfig.sequence` | Cron polling order (all active configs polled each tick) + UI list order | Failover priority |
| `MailTemplate.preferredServerConfigId` | Per-template preferred SMTP (e.g. marketing→SendGrid, transactional→Postmark). Scope-checked on write: only a config owned by the template's own tenant scope (platform configs are invisible to tenants) | Hard binding — DTO can still override |

Resolution failure is loud: when neither a tenant default nor a platform
default exists, `resolveSend()` / `resolveReceive()` log an ERROR (with the
tenant id) and reject the request with a `BusinessException` — an
ops-actionable signal that the deployment is missing its default server
config.

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
// IDs point at PENDING records; the consumer flips them to SENT/FAILED.
// To check terminal status, query MailSendRecordService.getById(recordId).
```

> **All mail sends are asynchronous.** `sendMail / sendMailBatch` persist a
> `MailSendRecord (PENDING)` + outbox row in one DB transaction
> and return immediately; SMTP delivery happens in the broker-driven consumer.
> There is deliberately no synchronous variant: an SMTP `250 OK` is **not** the
> same as "user has the email" — the user still waits seconds-to-minutes for the
> provider to deliver, so the ~500ms of broker latency is invisible, while a
> single async path avoids blocking HTTP threads and the stranded-`RETRY` edge case.

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

// Fetch from auto-resolved server
int fetched = mailReceiveService.fetchNewMails();

// Fetch from a specific server config
int fetchedByServer = mailReceiveService.fetchNewMails(serverConfigId);

// Mark as read
mailReceiveService.markAsRead(recordId);
mailReceiveService.markAsRead(List.of(id1, id2, id3));
```

Messages are deduplicated by `(server_config_id, message_id)`, so repeated
polling is safe. Bounce and read-receipt classification matches inbound mails
against the send log in a single batched `IN()` query; the matched
`MailSendRecord` is updated via CAS (see `markBounced` / `markReadReceiptReceived`).

### Scheduled Fetch

- Scheduled fetch is optional and requires `cron-starter`
- The current consumer listens to `mq.topics.cron-task.topic`
- When it receives a cron whose name starts with `mail-fetch`, it polls every
  receive config with `active = true` — across all tenants; each config's
  fetch runs inside that config's tenant context
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

- `Pending` — record created, waiting for the consumer or outbox publisher
- `Sending` — claimed by a consumer via CAS; SMTP send in flight
- `Sent` — SMTP server accepted the message
- `Retry` — transient failure; re-driven after `next_retry_at` elapses
- `Failed` — permanent SMTP reject or validation failure (bad recipient, auth, malformed input)
- `DeadLetter` — retry budget exhausted; ops intervention required

A record can transition `Sent → Failed` when an inbound bounce is correlated.

#### MailReceiveRecord

```text
Unread -> Read -> Archived
               -> Deleted
```

## SMS

### Core Logic

#### Config resolution

SMS sending uses the following default lookup order:

```text
1. Current tenant default SMS provider(s)
2. Platform default SMS provider(s) (`tenant_id = -1`) — only when the tenant
   has none; the tiers never interleave
3. BusinessException if nothing is available
```

If multiple records are marked as default, the one with the smallest `priority`
is used. Provider configs are cached in Redis (5 min TTL) and evicted on update
/ delete automatically.

#### Template resolution

SMS templates are resolved by `code` within the tier the send's scope names —
no cross-tier fallback:

```text
scope = TENANT (default) -> the current scope's own template (code + enabled)
scope = PLATFORM         -> the platform-tier template (tenant_id = -1)
```

Template placeholders use the unified Softa syntax: `{{ variable }}`.

#### Delivery pipeline

Identical to mail. Each recipient becomes one `SmsSendRecord`; the state machine
is the same six-state CAS flow (`PENDING → SENDING → SENT / FAILED / RETRY /
DEAD_LETTER`). Provider routing is resolved before persistence, and retry
replays the same provider/template parameters stored on the record.

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
// ids point at PENDING records; the consumer flips them to SENT/FAILED.
```

> **All SMS sends are asynchronous** — same contract as Mail. `sendSms /
> sendSmsBatch` enqueue an `SmsSendRecord (PENDING)` + outbox row and
> return immediately; the broker-driven consumer performs the provider
> call.

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

`sms_provider_region.tenant_id` follows the same rule as other tenant tables:
`-1` for platform-tier routing (invisible to tenants); `>0` for per-tenant
rows. Routing reads are per-country and tenant-first: for the recipient's
country the dispatcher uses the tenant's own rows when any exist, otherwise
the platform's rows for that country — the tiers never interleave within one
country.

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

When a template is bound to multiple eligible SMS providers, enqueue-time
planning selects one provider in `priority` order (lower = preferred):

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

## Operations

### Metrics

When Micrometer is on the classpath, `MessageMetrics` emits four counter
families:

| Name | Tags | When incremented |
|---|---|---|
| `softa.message.sent` | `channel` (mail/sms), `provider` | Provider call succeeded |
| `softa.message.failed` | `channel`, `provider`, `outcome` (retry/failed/dead_letter) | Provider call failed |
| `softa.message.outbox.published` | `route` | Outbox entry successfully published to broker |
| `softa.message.outbox.dead` | `route` | Outbox entry exceeded publish attempts → DEAD |

`softa.message.failed{outcome=dead_letter}` is emitted by `SendFailureHandler`
when a send record transitions to DEAD_LETTER.

### Delivery timing

Delivery is asynchronous, so its latency is read from persisted timestamps, not
in-process timers. A send record has one outbox row per attempt — the first
send, each retry, a sweeper revival, a manual retry — joined on
`aggregate_type` + `aggregate_id`:

| Moment | Column |
|---|---|
| Accepted | record `created_time` (same transaction as the first outbox row) |
| Published to the broker | `message_outbox.published_at` |
| Retry scheduled for | `message_outbox.next_attempt_at` (null on the first row) |
| Attempt started (consumer claim) | record `attempt_started_at` — latest attempt |
| Provider accepted | record `sent_at` |
| Provider call length | record `send_duration_ms` — the successful attempt |

For the attempt that succeeded: queue wait = `attempt_started_at` − the latest
outbox row's `published_at`; provider time = `send_duration_ms`; end to end =
`sent_at` − `created_time`. A failed attempt ends at the record's
`updated_time`, so its length is `updated_time` − `attempt_started_at` until the
next claim overwrites it. `created_time` is taken inside the accepting
transaction, so a long caller transaction shows up as publish delay. MySQL
`DATETIME` columns hold whole seconds (PostgreSQL `TIMESTAMP` keeps
microseconds); `send_duration_ms` is exact to the millisecond on both.

### Inbound delivery status

Mail bounce and read-receipt status is derived from **inbound mail** on the
IMAP receive path — DSN report emails (`DsnRule` / `MailerDaemonRule` →
`BounceReceiptLinker` → `markBounced`) and MDN emails (`ReadReceiptRule`). No
inbound HTTP callback is provided; for provider-pushed delivery events (SMS
DLR, ESP mail events) add a controller in your application that calls the
record services' CAS transitions.

### Rate limits

`MailSendServerConfig` and `SmsProviderConfig` carry two quota columns:

- `daily_send_limit` — cumulative sends per day
- `rate_limit_per_minute` — sends per minute (smooths bursts)

Either can be left null/zero to disable that window. Counters live in Redis
(`rl:{channel}:{daily|min}:{tenantId}:{configId}:{window}`), so multi-instance
deployments share one budget. A quota breach surfaces as a provider-side
`QUOTA_EXCEEDED` error, classified as `ErrorCategory.QUOTA` — the retry policy
applies the configured `quotaFloorSeconds` (default 5 min) so we don't hammer
the provider.

### Zombie record sweeper

`ZombieRecordSweeper` runs every minute. Records stuck in `SENDING` whose
`updated_time` is older than `softa.message.zombie.stale-seconds` (default
300) are versionLock-transitioned back to `RETRY` with `next_retry_at = now`
and a retry outbox row is written in the same transaction. Stale outbox
`PUBLISHING` claims are reopened to `NEW`. This covers JVM crashes between
claiming a record and finishing the provider/broker call.

Each revival counts as a retry and is held to the same budget as a failed send:
a stuck record whose `retry_count` has reached
`softa.message.retry.default-max-attempts` is moved to `DEAD_LETTER` (error code
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

Disable via `softa.message.zombie.enabled=false` on read-only replicas.

### Sensitive field encryption

Credential columns on the config tables (`mail_*_server_config.password`,
`sms_provider_config.api_secret`) are defined wide enough to hold ciphertext.
The framework's transparent encryption (`MetaField.isEncrypted()`)
handles the read/write side — but you must still **mark these fields as
encrypted in the `SysField` metadata table** during deployment. See
`framework/softa-orm` docs for the full procedure; out of the box the columns
store plaintext.

## Extension Points

### Mail transport

Mail sending is SMTP-only. `MailSendServerConfig` is the complete outgoing
server configuration. `SmtpMailTransport` keeps authenticated connections open
between sends, one small pool per server config, so a run of mails through one
server pays the TCP connect, TLS handshake and AUTH once instead of per message.
An idle connection is probed with `NOOP` before reuse. A connection that saw a
failure, carried `max-messages-per-connection` messages, or sat idle past
`idle-timeout` is closed instead. Keep `idle-timeout` below the idle cut-off of
any NAT or load balancer in front of the SMTP server: a connection dropped
silently would only answer the probe with a read timeout.

Each pool is keyed by the settings its connections were opened with, so an
edited config (new host, rotated password) opens fresh connections on its next
send, and config changes still only need the Redis config cache evicted
(automatic on update/delete). `softa.message.mail.smtp-pool.enabled=false`
goes back to one connection per send. Connectivity tests always open a new
connection.

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

Failed sends are retried by `ExponentialBackoffPolicy` — exponential back-off
with a configurable base, multiplier, cap, and ±jitter, tuned via
`softa.message.retry.exponential.*`. The error category from `ErrorClassifier`
decides the disposition: TRANSIENT / QUOTA / UNKNOWN retry (QUOTA clamped to
`quota-floor-seconds`) until `default-max-attempts` is reached and the record
is dead-lettered; PERMANENT / INVALID_INPUT / AUTH fail immediately without
retry. `RetryDecision` is a sealed type (`Retry` / `Fail` / `DeadLetter`) so the
failure handler's `switch` stays exhaustive.
