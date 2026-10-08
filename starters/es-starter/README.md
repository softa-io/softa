# ES Starter

Elasticsearch-backed **change-log** persistence and search. The framework emits
a change event for every business-data mutation (when `system.enable-change-log`
is on); this starter consumes those events from Pulsar, indexes them into
Elasticsearch, and exposes a query API for the resulting audit trail. It also
provides a small reusable `ESService<T>` query abstraction.

> Scope note: this is a change-log / audit-search module, **not** a general
> CRUD layer for arbitrary entities — it has no automatic indexing of business
> models. `ESService<T>` is a query contract for ES-backed data; the concrete
> implementation shipped here is for change logs.

## Dependency

```xml
<dependency>
  <groupId>io.softa</groupId>
  <artifactId>es-starter</artifactId>
  <version>${softa.version}</version>
</dependency>
```

Depends on `softa-web` and `spring-boot-starter-data-elasticsearch`.

## How it works

```
business mutation ──▶ change-log event ──▶ Pulsar topic ──▶ ChangeLogPersistConsumer ──▶ Elasticsearch index
   (softa-orm,                             (mq.topics.        (bulk-index as              (spring.elasticsearch
    enable-change-log)                      change-log)        ChangeLogDocument)          .index.changelog)
```

- `ChangeLogPersistConsumer` — Pulsar listener; registered **only** when
  `mq.topics.change-log.topic` is configured (`@ConditionalOnProperty`). It
  converts each event to a `ChangeLogDocument` and bulk-indexes it.
- `ChangeLogDocument` — the stored shape (map payloads flattened to JSON strings
  for deterministic serialization). The payloads are not indexed, so two
  keyword fields carry what queries need from them:

  | Field | Holds | Example |
  |---|---|---|
  | `refs` | one `field=id` entry per many-to-one field of the row | `["employeeId=1001", "salaryProfileItemId=2002"]` |
  | `changedFields` | the fields an UPDATE wrote (empty for CREATE / DELETE) | `["amount", "currency"]` |

  `refs` is what finds the rows of a one-to-many relation that were deleted
  since; `changedFields` is what drops an UPDATE that only wrote fields the
  reader may not see. For an UPDATE to carry `refs`, the write path reads the
  row's many-to-one keys and its `displayName` fields in the same SELECT that
  fetches the values being replaced, and logs them in `dataBeforeChange`. The
  values are stored raw (ids, option codes); references and options are turned
  into display text when the log is read, so a renamed reference shows its
  current name.

## Query API

`ChangeLogController`:

| Endpoint | Purpose |
|---|---|
| `GET /ChangeLog/getChangeLog` | Change history for one row (`modelName` + `id`, paged) |
| `GET /ChangeLog/getSliceChangeLog` | History for a timeline-model slice |
| `GET /ChangeLog/getRecordChangeLog` | One record's history together with the rows of the one-to-one / one-to-many fields named in `relations` (comma separated), as one page |
| `POST /ChangeLog/searchPageByModel` | Filtered search within a model (`QueryParams` body) |
| `POST /ChangeLog/searchPage` | Cross-model search (`QueryParams` body) |

Admin-scoped endpoints require the system admin role; results are permission-
checked per user and field references are resolved for display.

**Values are masked per record.** Every read of the log — `searchPage` and
`getRecordChangeLog` alike — passes `visibleToReader`: each entry's before and
after values go through `PermissionService.maskRows` as rows of their own model
keyed by the record's id, and a field hidden on that record is removed from both
sides (a cleared value would otherwise show what it was cleared from). Fields
hidden on every record are then dropped, and an update left with nothing visible
is dropped whole. Visibility is judged on the record as it is now: a scope
condition on a field that changes over time re-judges the whole history.

`getRecordChangeLog` matches a one-to-one relation by the current row's id, and
a one-to-many relation by the current rows' ids (`sliceId` for a timeline model)
OR a `refs` entry pointing back at the record, so deleted rows stay in the
history; past 200 current rows it matches by `refs` alone. A relation whose
model the reader cannot read is left out.

Fields the reader may not see are taken out of every result: they are removed
from the payloads, an UPDATE that wrote nothing else is not returned, and a
DELETE keeps only the fact that a row was deleted. `searchPageByModel` also
returns only the logs of rows the reader can currently read.

`ESService<T>` / `ESServiceImpl<T>` provide `searchPage(Filters, Orders, Page)`
with criteria mapping (EQUAL, NOT_EQUAL, GREATER_THAN, CONTAINS, IN, BETWEEN,
PARENT_OF, CHILD_OF, …).

## Configuration

```yaml
system:
  enable-change-log: true              # framework emits change events (required to produce data)

spring:
  elasticsearch:
    uris: http://localhost:9200
    username: <user>
    password: <pass>
    index:
      changelog: demo_change_log        # target ES index (required by the consumer)

mq:
  topics:
    change-log:
      topic: demo_change_log            # Pulsar topic the events are published to
      persist-sub: demo_change_log_persist_sub   # subscription for the consumer
```

All are mandatory when using the module (no defaults).

## Infrastructure

Needs a reachable **Elasticsearch** instance, and a **Pulsar** broker for the
change-log pipeline. The demo stack (`deploy/demo-app/docker-compose.yml`)
provides both (plus Kibana). Business data still lives in MySQL/PostgreSQL; only
the change-log audit trail is indexed into ES.
