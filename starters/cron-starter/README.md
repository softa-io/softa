# Cron Starter

## Overview
This starter provides a distributed cron scheduler. It loads `SysCron` records from the database, elects a single
leader via Redis, and publishes cron triggers to MQ (Pulsar) as `CronTaskMessage`. Your application should consume
those messages and execute the real business logic.

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
- `active`: active flag.

### SysCronLog
Execution log for jobs. The Flow starter writes logs when it consumes cron tasks.

### CronTaskMessage
Fields published to MQ:
- `cronId`, `cronName`
- `triggerTime`, `lastExecTime`
- `context` (request context snapshot)

## Scheduling Behavior
- Only one scheduler runs across the cluster, elected by Redis key `cron:scheduler:leader`.
- Lease duration is 60s, renewal interval is 30s.
- The scheduler uses `ZoneId.systemDefault()`.
- A cron with `limitExecution=false` is scheduled repeatedly.
- A cron with `limitExecution=true` is scheduled with an in-memory counter only. `remainingCount` is not persisted by the scheduler.

Important behavior note:
- On startup, the scheduler currently registers jobs where `active` is NOT `true` (null/false). If your expectation is
  "active means enabled", align data or adjust the condition in code.

## How To Use
### 1. Create a cron job
Use one of the following ways to create cron jobs. UI creation is the primary recommended path.

1. UI (Recommended)
Create a `SysCron` record through your admin UI or metadata console. After saving, activate the job if needed.

2. Predefined data
Seed `SysCron` records during system initialization or migrations, then activate them when the service starts.

3. SQL insert
Insert into the `SysCron` table directly (fields may vary by schema).

```sql
INSERT INTO sys_cron
  (name, cron_expression, cron_semantic, limit_execution, remaining_count, active)
VALUES
  ('DailyReport', '0 0 2 ? * *', 'At 02:00 AM', false, 0, true);
```

### 2. Execute once immediately
The controller exposes endpoints for immediate execution:
- `POST /SysCron/executeNow?id=123`
- `POST /SysCron/executeMultipleNow?ids=1&ids=2`

### 3. Consume cron tasks
Implement a Pulsar consumer to run business logic when a cron triggers.

```java
@Component
@ConditionalOnProperty(name = "mq.topics.cron-task.topic")
public class CustomCronConsumer {

    @PulsarListener(
        topics = "${mq.topics.cron-task.topic}",
        subscriptionName = "${mq.topics.cron-task.flow-sub}"
    )
    public void onMessage(CronTaskMessage message) {
        // TODO: run your job
        // message.getCronName(), message.getTriggerTime(), message.getContext()
    }
}
```

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
