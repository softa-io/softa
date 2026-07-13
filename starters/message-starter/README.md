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
| Redis | `RateLimiter` (per-tenant + per-config quotas), `MailConfigCache`, send-quota counters | Operation throws; caller sees 5xx | Sentinel or Cluster setup. K8s `readinessProbe` should include `/actuator/health/redis` so the load balancer routes traffic away while Redis is unreachable. |
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
ORM. `tenant_id = 0` rows form the **platform tier**, shared by every tenant:

- Config/template/routing resolution is **overlay-style**: the caller's own
  rows plus the platform tier (tenant default → platform default; tenant
  template → platform template; routing = union of both, by priority).
- Background jobs are cross-tenant scans that execute per-record in the owning
  tenant's context: the scheduled mail fetch runs each receive config inside
  its config's tenant, and the zombie sweeper revives each stuck record inside
  its record's tenant.
- The transactional outbox and the dead-letter store are shared infrastructure
  tables; tenant identity travels inside the message payload
  (`recordId / tenantId / traceId`) and is restored by the consumer.

With multi-tenancy disabled, no filtering or stamping occurs and everything
behaves single-tenant.

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
    sms:
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
2. Platform default mail server (`tenant_id = 0`)
3. BusinessException if nothing is available
```

If multiple records are marked as default, the one with the smallest `sequence`
is used. Config objects are cached in Redis for 5 minutes; updating a config
via `MailSendServerConfigService.updateOne` / `deleteById` evicts automatically.

#### Template resolution

Email templates are resolved by `code` with a platform fallback:

```text
tenant template (code + enabled)
  -> platform template (tenant_id = 0)
  -> BusinessException
```

Template placeholders use the unified Softa syntax: `{{ variable }}`.

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
               FAILED (permanent error: bad recipient, auth, malformed input)
```

On failure, `ErrorClassifier` maps the provider error to an `ErrorCategory`
(TRANSIENT / PERMANENT / INVALID_INPUT / AUTH / QUOTA / UNKNOWN), and the
retry policy (`ExponentialBackoffPolicy`) decides:

- **Retry** → `markRetry(nextRetryAt = now + backoff)` + enqueue a delayed
  outbox row on `mail-send` so the same delivery consumer re-drives it
- **Fail** → `markFailed` (terminal; no retry; permanent provider reject)
- **DeadLetter** → `markDeadLetter` + archive a `dead_letter_message` row (`source = SendExhausted`)

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
  ↓ none found
BusinessException
```

Once a config is chosen, that's it — there is no "primary failed, try
secondary" behaviour. SMTP failure goes through the normal retry policy
(retry against the same server with backoff), not server-switching.

#### What the fields mean

| Field | Used for | NOT used for |
|---|---|---|
| `MailSendServerConfig.isDefault` | Marks tenant/platform default candidate | Failover (only the first default is ever picked) |
| `MailSendServerConfig.sequence` | Tie-break among multiple `isDefault=true` rows + UI list order | Failover priority |
| `MailReceiveServerConfig.sequence` | Cron polling order (all enabled configs polled each tick) + UI list order | Failover priority |
| `MailTemplate.preferredServerConfigId` | Per-template preferred SMTP (e.g. marketing→SendGrid, transactional→Postmark) | Hard binding — DTO can still override |

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
  "isEnabled": true
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
  receive config with `isEnabled = true` — across all tenants; each config's
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
1. Current tenant default SMS provider
2. Platform default SMS provider (`tenant_id = 0`)
3. BusinessException if nothing is available
```

If multiple records are marked as default, the one with the smallest `priority`
is used. Provider configs are cached in Redis (5 min TTL) and evicted on update
/ delete automatically.

#### Template resolution

SMS templates are resolved by `code` with a platform fallback:

```text
tenant template (code + enabled)
  -> platform template (tenant_id = 0)
  -> BusinessException
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
  "isEnabled": true
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
`0` for platform-level routing (shared by all tenants); `>0` for per-tenant
overrides. Routing reads are platform-overlay: the dispatcher sees the union
of platform rows and the caller's own tenant rows, interleaved by priority.

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
server configuration; `SmtpMailTransport` is stateless and builds a fresh
Jakarta Mail sender per send, so config changes only need the Redis config
cache evicted (automatic on update/delete).

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
