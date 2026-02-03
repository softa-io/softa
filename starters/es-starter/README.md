# Configuration
## ES index:
    spring.elasticsearch.index.changelog
## MQ Topic:
```yml
mq:
  topics:
    change-log:
      topic: dev_demo_change_log
      persist-sub: dev_demo_change_log_persist_sub
```