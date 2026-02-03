# Configuration
## MQ Topic:
```yml
mq:
  topics:
    change-log:
      topic: dev_demo_change_log
      flow-sub: dev_demo_change_log_flow_sub
    cron-task:
      topic: dev_demo_cron_task
      flow-sub: dev_demo_cron_task_flow_sub
    flow-async-task:
      topic: dev_demo_flow_async_task
      sub: dev_demo_flow_async_task_sub
    flow-event:
      topic: dev_demo_flow_event
      sub: dev_demo_flow_event_sub
```