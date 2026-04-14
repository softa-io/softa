# Message Starter

Provides unified messaging capabilities for Softa applications:

- **Email**: send emails, receive emails, and render email templates
- **SMS**: send SMS messages, batch send, render templates, and retry on failure
- **Inbox**: push notifications and create actionable todo items for users

## Dependency

```xml
<dependency>
  <groupId>io.softa</groupId>
  <artifactId>message-starter</artifactId>
  <version>${softa.version}</version>
</dependency>
```

## Requirements and Configuration

- Apply `src/main/resources/sql/message-starter.sql` for email and inbox tables.
- Apply `src/main/resources/sql/message-starter-sms.sql` for SMS tables.
- Async send uses Pulsar only when the corresponding `mq.topics.*.topic` is configured; otherwise it falls back to Spring `@Async`.
- Delayed retry requires both `maxRetryCount > 0` and the corresponding retry topic. Without a retry topic, failed sends are marked `Failed` directly.
- Scheduled mail fetching requires `cron-starter` on the classpath and `mq.topics.cron-task.topic`.

Minimal MQ configuration, define only the topics you actually use:

```yml
mq:
  topics:
    mail-send:
      topic: dev_demo_mail_send
      sub: dev_demo_mail_send_sub
    mail-retry:
      topic: dev_demo_mail_retry
      sub: dev_demo_mail_retry_sub
    sms-send:
      topic: dev_demo_sms_send
      sub: dev_demo_sms_send_sub
    sms-retry:
      topic: dev_demo_sms_retry
      sub: dev_demo_sms_retry_sub
    cron-task:
      topic: dev_demo_cron_task
      mail-fetch-sub: dev_demo_cron_task_mail_fetch_sub
```

## Email

### Core Logic

#### Config resolution

Email sending uses the following default lookup order:

```text
1. Current tenant default mail server
2. Platform default mail server (`tenant_id = 0`)
3. BusinessException if nothing is available
```

If multiple records are marked as default, the one with the smallest `sortOrder` is used.

#### Template resolution

Email templates use the following fallback logic:

```text
tenant + current language
  -> tenant + default language
  -> platform + current language
  -> platform + default language
  -> BusinessException
```

Template placeholders use the unified Softa syntax: `{{ variable }}`.

#### Async delivery and retry

- `sendAsync(...)` returns immediately and performs delivery in the background
- If `mq.topics.mail-send.topic` is configured, async send goes through Pulsar
- Otherwise async send falls back to Spring `@Async`
- Every send automatically creates a `MailSendRecord`
- Delayed retry happens only when `maxRetryCount > 0` and `mq.topics.mail-retry.topic` is configured

Business code usually does not need to choose a mail server explicitly. Defaults should be prepared
by the platform or tenant admin.

### Sending Email

Inject `MailSendService` where email delivery is needed:

```java
@Autowired
private MailSendService mailSendService;

// Plain text
mailSendService.sendText("alice@example.com", "Hello", "Welcome to Softa.");

// HTML
mailSendService.sendHtml("alice@example.com", "Welcome", "<h1>Hello Alice</h1>");

// Multiple recipients
mailSendService.sendHtml(List.of("a@x.com", "b@x.com"), "Notice", "<p>...</p>");

// Full control
SendMailDTO dto = new SendMailDTO();
dto.setTo(List.of("alice@example.com"));
dto.setCc(List.of("manager@example.com"));
dto.setSubject("Offer Letter");
dto.setHtmlBody("<p>Dear Alice...</p>");
dto.setAttachments(List.of(attachment));
mailSendService.sendNow(dto);

// Async
mailSendService.sendAsync(dto);
```

### Differentiated Batch

```java
SendMailDTO dto = new SendMailDTO();
dto.setTemplateCode("ORDER_CONFIRMATION");

BatchMailItemDTO alice = new BatchMailItemDTO();
alice.setTo(List.of("alice@example.com"));
alice.setTemplateVariables(Map.of("orderNo", "SO-1001", "name", "Alice"));

BatchMailItemDTO bob = new BatchMailItemDTO();
bob.setTo(List.of("bob@example.com"));
bob.setTemplateVariables(Map.of("orderNo", "SO-1002", "name", "Bob"));

dto.setItems(List.of(alice, bob));
mailSendService.sendNow(dto);
```

### Attachments

```java
MailAttachmentDTO attachment = new MailAttachmentDTO();
attachment.setFileName("report.pdf");
attachment.setContentType("application/pdf");
attachment.setData(pdfBytes);
```

For outbound send, the current implementation attaches only `data`. `fileId` is not resolved automatically during send.

### Email Templates

Use templates when business content should be reusable or multilingual:

```java
@Autowired
private MailSendService mailSendService;

Map<String, Object> vars = Map.of(
    "name", "Alice",
    "activationUrl", "https://app.example.com/activate/abc123"
);

mailSendService.sendByTemplate("USER_WELCOME", "alice@example.com", vars);
mailSendService.sendByTemplate("ORDER_CONFIRMATION", List.of("a@x.com", "b@x.com"), vars);
```

The current request language is taken from `ContextHolder`.

#### Template example

```bash
POST /MailTemplate/createOne
{
  "code": "USER_WELCOME",
  "name": "User Welcome Email",
  "language": "en-US",
  "subject": "Welcome, {{ name }}!",
  "body": "<h1>Welcome, {{ name }}</h1><p><a href='{{ activationUrl }}'>Activate</a></p>",
  "includePlainText": true,
  "isEnabled": true
}
```

Use `language: "default"` to define a fallback template.

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

Messages are deduplicated by `(server_config_id, message_id)`, so repeated polling is safe.

### Scheduled Fetch

- Scheduled fetch is optional and requires `cron-starter`
- The current consumer listens to `mq.topics.cron-task.topic`
- When it receives a cron whose name starts with `mail-fetch`, it polls every enabled receive config with `scheduledFetchEnabled = true`
- `fetchCronExpression` is metadata on the config record; it does not register a per-config scheduler by itself in this module

### Email Status Reference

#### MailSendRecord

```text
Pending -> Sent
Pending -> Failed
Pending -> Retry
```

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

If multiple records are marked as default, the one with the smallest `sortOrder` is used.

#### Template resolution

SMS templates use the following fallback logic:

```text
tenant + current language
  -> tenant + default language
  -> platform + current language
  -> platform + default language
  -> BusinessException
```

Template placeholders use the unified Softa syntax: `{{ variable }}`.

#### Async, failover, and retry

- `sendAsync(...)` returns immediately and performs delivery in the background
- If `mq.topics.sms-send.topic` is configured, async send goes through Pulsar
- Otherwise async send falls back to Spring `@Async`
- Each recipient automatically creates one `SmsSendRecord`
- For template-based sends, bound providers are tried in `sortOrder`
- Delayed retry happens only when `maxRetryCount > 0` and `mq.topics.sms-retry.topic` is configured

Business code usually does not need to choose a provider explicitly. Defaults and bindings should be
prepared by the platform or tenant admin.

### Sending SMS

Inject `SmsSendService` where SMS delivery is needed:

```java
@Autowired
private SmsSendService smsSendService;

// Plain text to a single recipient
smsSendService.sendNow("+1234567890", "Your verification code is 123456");

// Full control via DTO
SendSmsDTO dto = new SendSmsDTO();
dto.setPhoneNumber("+1234567890");
dto.setContent("Order #1234 has been shipped.");
smsSendService.sendNow(dto);

// Uniform batch
SendSmsDTO batchDto = new SendSmsDTO();
batchDto.setPhoneNumbers(List.of("+1111111111", "+2222222222"));
batchDto.setContent("System maintenance tonight at 10pm.");
smsSendService.sendNow(batchDto);

// Async
smsSendService.sendAsync(dto);
```

### Send Modes

| Mode | Main fields | Description |
|---|---|---|
| Single | `phoneNumber` + `content` | Send to one recipient |
| Uniform batch | `phoneNumbers` + `content` | Same content to all recipients |
| Differentiated batch | `items` | Per-recipient variables or content |
| Template API | `sendByTemplate(code, phoneNumber(s), variables)` | Render then send |

### Differentiated Batch Example

```java
SendSmsDTO dto = new SendSmsDTO();
dto.setTemplateCode("ORDER_STATUS");

BatchSmsItemDTO first = new BatchSmsItemDTO();
first.setPhoneNumber("+111");
first.setTemplateVariables(Map.of("orderId", "A001", "status", "Shipped"));

BatchSmsItemDTO second = new BatchSmsItemDTO();
second.setPhoneNumber("+222");
second.setTemplateVariables(Map.of("orderId", "B002", "status", "Delivered"));

dto.setItems(List.of(first, second));
smsSendService.sendNow(dto);
```

### SMS Templates

```java
Map<String, Object> vars = Map.of("code", "123456", "minutes", 5);

smsSendService.sendByTemplate("VERIFY_CODE", "+1234567890", vars);
smsSendService.sendByTemplate("VERIFY_CODE", List.of("+111", "+222"), vars);
```

#### Template example

```bash
POST /SmsTemplate/createOne
{
  "code": "VERIFY_CODE",
  "name": "Verification Code",
  "language": "en-US",
  "content": "Your verification code is {{ code }}. Valid for {{ minutes }} minutes.",
  "isEnabled": true
}
```

### SMS Failover and Retry

When a template is bound to multiple SMS providers, delivery tries them in `sortOrder`:

```text
Provider 1 -> Provider 2 -> Provider 3
```

- Stop on the first successful provider
- If all providers fail, the record becomes `Failed`
- If retry is enabled and the retry topic is configured, the record can enter `Retry` and be attempted again later
- During retry, the full failover chain starts from the beginning

This logic matters for business developers mainly when they need to understand why a send eventually
succeeded or why the final record became `Failed` or `Retry`.

### SMS Status Reference

#### SmsSendRecord

```text
Pending -> Sent
Pending -> Failed
Pending -> Retry
```

## Inbox

### Core Logic

- Inbox is used for in-app communication rather than external channel delivery
- `InboxNotification` is read-only information sent to users
- `InboxTodo` is an actionable work item that can be completed, rejected, cancelled, or expired
- `flow-starter` can create todos during approval or review steps

### Inbox Notifications

Use `InboxService` to push read-only notifications to users:

```java
@Autowired
private InboxService inboxService;

inboxService.notify(userId, "Order shipped", "Your order #1234 has been dispatched.");
inboxService.notify(List.of(userId1, userId2), "System update", "Scheduled maintenance tonight.");

int unread = inboxService.countUnread(userId);
inboxService.markAsRead(notificationId);
inboxService.markAllAsRead(userId);
```

### Inbox Todos

Use todos for approvals, reviews, and pending business actions:

```java
@Autowired
private InboxService inboxService;

InboxTodo todo = inboxService.createTodo(
    approverId,
    "Approve leave request",
    "Employee Alice has requested 3 days of annual leave.",
    "FLOW_INSTANCE", flowInstanceId,
    "/flow/approval/" + flowInstanceId
);

inboxService.completeTodo(todoId);
inboxService.rejectTodo(todoId);
inboxService.cancelTodo(todoId);

int pending = inboxService.countPendingTodos(assigneeId);
```

### Inbox Status Reference

#### InboxTodo

```text
Pending -> Done
Pending -> Rejected
Pending -> Cancelled
Pending -> Expired
```
