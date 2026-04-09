# Cron Starter

## Overview
This starter provides a distributed cron scheduler. It loads `SysCron` records from the database, elects a single
leader via Redis, and publishes cron triggers to MQ (Pulsar) as `CronTaskMessage`. Your application consumes those
messages and executes the real business logic.

The scheduling side and the execution side are intentionally decoupled:
- `cron-starter` decides when to trigger and what context to send.
- Your MQ consumer decides what business code to run after the trigger arrives.

## Dependency
```xml
<dependency>
  <groupId>io.softa</groupId>
  <artifactId>cron-starter</artifactId>
  <version>${softa.version}</version>
</dependency>
```

## Requirements
- Redis is required for leader election (`StringRedisTemplate`).
- Pulsar is required for task delivery (`PulsarTemplate`).
- Database contains the `SysCron` / `SysCronLog` metadata tables.
- If you run in shared-db multi-tenancy mode, the runtime must also provide `TenantInfoService`
  (normally with `system.enable-multi-tenancy=true` and the `tenant_info` model/table available).

## Configuration
### MQ topic
```yml
mq:
  topics:
    cron-task:
      topic: dev_demo_cron_task
      flow-sub: dev_demo_cron_task_flow_sub
```

### Scheduler thread pool size
```yml
cron:
  threads:
    number: 1
```

### Multi-tenancy switch
`cron-starter` itself does not add a separate cron-specific tenant switch. It follows the ORM/runtime tenant mode:

```yml
system:
  enable-multi-tenancy: true
```

When multi-tenancy is disabled, `TenantInfoService` is not expected and tenant-aware fan-out is not available.

## Data Model
### SysCron
Key fields:
- `name`: cron job name.
- `cronExpression`: Quartz cron expression.
- `cronSemantic`: human readable description (see `CronUtils.cronSemantic`).
- `limitExecution`: `true` to enable limited executions.
- `remainingCount`: remaining executions when `limitExecution=true`.
- `nextExecTime`, `lastExecTime`: timestamps for reference or UI.
- `redoMisfire`, `priority`: reserved for future use in scheduler.
- `description`: free text.
- `tenantJobMode`: optional tenant dispatch mode. Option values are `PerTenant` and `CrossTenant`.
- `active`: active flag.

### SysCronLog
Execution log for jobs. The Flow starter writes logs when it consumes cron tasks.

### CronTaskMessage
Fields published to MQ:
- `cronId`, `cronName`
- `triggerTime`, `lastExecTime`
- `context` (request context snapshot used by the consumer)

## Scheduling Flow
1. One node becomes leader by holding Redis key `cron:scheduler:leader`.
2. The leader loads all `SysCron` records from metadata storage.
3. Each job is converted into a Quartz schedule and registered in the local scheduler pool.
4. When a trigger fires, the scheduler publishes one or more `CronTaskMessage` messages.
5. Consumers restore `message.context` and execute the real business logic.

Implementation notes:
- Only one scheduler runs across the cluster at a time.
- Lease duration is 60s, renewal interval is 30s.
- The scheduler uses `ZoneId.systemDefault()`.
- A cron with `limitExecution=false` is scheduled repeatedly.
- A cron with `limitExecution=true` is scheduled with an in-memory counter only. `remainingCount` is not persisted by the scheduler.
- On startup, the current implementation registers jobs where `active` is NOT `true` (`null` / `false`). If your expectation is
  "active means enabled", align data or adjust the condition in code.

## Tenant Modes
### Non-multi-tenant mode
Use this when `system.enable-multi-tenancy=false` or your application does not provide `TenantInfoService`.

Recommended `SysCron` setup:
- leave `tenantJobMode` empty / null

Execution behavior:
- one MQ message is published for each trigger
- `message.context.tenantId` is not expanded
- `message.context.crossTenant=false`

Notes:
- `tenantJobMode=PerTenant` is not supported here. The scheduler logs an error and skips dispatch because it cannot list active tenants.
- `tenantJobMode=CrossTenant` is technically harmless, but it has no practical meaning when the runtime is not in multi-tenant mode.

### Shared-db multi-tenant mode
Use this when `system.enable-multi-tenancy=true` and `TenantInfoService` is available.

#### Mode 1: default single-dispatch
Recommended `SysCron` setup:
- leave `tenantJobMode` empty / null

Execution behavior:
- one MQ message is published
- the current scheduler context is copied as-is

Use this only when you intentionally want "single dispatch with inherited context".
For tenant-wide batch work, prefer `PerTenant` or `CrossTenant` explicitly.

#### Mode 2: per-tenant dispatch
Recommended `SysCron` setup:
- `tenantJobMode=PerTenant`

Execution behavior:
- the scheduler loads all active tenant IDs from `TenantInfoService`
- one MQ message is published per active tenant
- each message gets its own `context.tenantId`
- each message keeps `context.crossTenant=false`

Typical use cases:
- send monthly reports tenant by tenant
- run tenant-local cleanup jobs
- synchronize tenant-isolated business data

#### Mode 3: cross-tenant dispatch
Recommended `SysCron` setup:
- `tenantJobMode=CrossTenant`

Execution behavior:
- one MQ message is published
- `context.tenantId` is left empty
- `context.crossTenant=true`

Typical use cases:
- global reconciliation
- system-wide statistics
- admin maintenance that must scan across all tenants

## Consumer-side Execution Logic
The scheduler already decides the tenant context and puts it into `CronTaskMessage.context`.
Consumers should restore that context before calling ORM-backed services.

Example:
```java
@Component
@ConditionalOnProperty(name = "mq.topics.cron-task.topic")
public class CustomCronConsumer {

    @PulsarListener(
        topics = "${mq.topics.cron-task.topic}",
        subscriptionName = "${mq.topics.cron-task.flow-sub}"
    )
    public void onMessage(CronTaskMessage message) {
        Runnable task = () -> {
            // TODO: run your business logic here
            // message.getCronName(), message.getTriggerTime(), message.getLastExecTime()
        };

        if (message.getContext() != null) {
            ContextHolder.runWith(message.getContext(), task);
        } else {
            task.run();
        }
    }
}
```

Important rule:
- If `SysCron.tenantJobMode=PerTenant`, do not also annotate the downstream business entry method with `@PerTenant`,
  otherwise the job will be expanded twice.

## How To Use
### 1. Create a cron job
Use one of the following ways to create cron jobs. UI creation is the primary recommended path.

1. UI (Recommended)
Create a `SysCron` record through your admin UI or metadata console. Set `tenantJobMode` only when you need
multi-tenant fan-out or cross-tenant execution.

2. Predefined data
Seed `SysCron` records during system initialization or migrations, then activate them when the service starts.

3. SQL insert
Insert into the `SysCron` table directly (fields may vary by schema).

```sql
INSERT INTO sys_cron
  (name, cron_expression, cron_semantic, tenant_job_mode, limit_execution, remaining_count, active)
VALUES
  ('DailyReport', '0 0 2 ? * *', 'At 02:00 AM', 'PerTenant', false, 0, true);
```

### 2. Execute once immediately
The controller exposes endpoints for immediate execution:
- `POST /SysCron/executeNow?id=123`
- `POST /SysCron/executeMultipleNow?ids=1&ids=2`

## Quartz Cron Expression
Format:
```
* * * ? * * [*]
- - - - - -  -
| | | | | |  |
| | | | | |  +- Year              (OPTIONAL)
| | | | | +---- Day of the Week   (range: 1-7 or SUN-SAT, 1 standing for Monday)
| | | | +------ Month             (range: 1-12 or JAN-DEC)
| | | +-------- Day of the Month  (range: 1-31)
| | +---------- Hour              (range: 0-23)
| +------------ Minute            (range: 0-59)
+-------------- Second            (range: 0-59)
```

Special characters:
- `*` match any
- `?` no specific value
- `,` list separator
- `-` range
- `/` increments

Examples:
- `0 0 2 ? * *` run every day at 02:00
- `0 */5 * ? * *` run every 5 minutes
- `0 30 9 ? * MON-FRI` run on weekdays at 09:30
