# Configuration
## MQ Topic:
```yml
mq:
  topics:
    async-import:
      topic: dev_demo_async_import
      sub: dev_demo_async_import_sub
```

# OSS Configuration
## Minio Configuration
```yml
oss:
  type: minio
  endpoint: http://minio:9000
  access-key: minioadmin
  secret-key: minioadmin
  bucket: dev-demo
```

## Storage policy
* General Path: modelName/TSID/fileName
* Multi-tenancy path: tenantId/modelName/TSID/fileName