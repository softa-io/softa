# Softa Web

## Option Sets
Option sets provide a shared, ordered list of selectable values. The web module exposes an API to read option items
and the ORM layer caches them for fast lookup.

### Data Source
Option items are stored in the metadata table `SysOptionItem`. Each item belongs to an `optionSetCode` and has an
`itemCode`, `label`, and a `sequence` used for ordering.

### Cache Behavior
At application startup, `OptionManager.init()` loads all `SysOptionItem` rows, ordered by `sequence`, and stores them
in an in-memory cache keyed by `optionSetCode`. Retrieval preserves the original order.

### API Usage
Read option items by option set code:

```
GET /SysOptionSet/getOptionItems/{optionSetCode}
```

Example:
```
GET /SysOptionSet/getOptionItems/OrderStatus
```

Response is a list of `MetaOptionItem` objects.

### Programmatic Usage
Use `OptionManager` in service code:

```java
List<MetaOptionItem> items = OptionManager.getMetaOptionItems("OrderStatus");
MetaOptionItem pending = OptionManager.getMetaOptionItem("OrderStatus", "PENDING");
String pendingName = OptionManager.getLabelByCode("OrderStatus", "PENDING");
boolean exists = OptionManager.existsItemCode("OrderStatus", "PENDING");
```

### Localization
`MetaOptionItem.getLabel()` returns a translated name if a translation exists for the current language in context.
If no translation exists, it returns the original `label`.

### OptionReference Structure
When option values are expanded or returned as references, they use `OptionReference`.

Fields:
- `itemCode`: option item code.
- `label`: option item display name.
- `itemTone`: optional semantic tone (e.g. `success`, `warning`, `error`, `info`, `neutral`).
- `itemIcon`: optional icon code (e.g. `check`, `x`, `ban`, `alert`, `pause`, `info`, `eye`, `loader`, `clock`, `pending`, `undo`, `lock`).

### Notes
- `OptionManager` throws if `optionSetCode` does not exist in cache. Validate with
  `OptionManager.existsOptionSetCode` when needed.
- Option set data must be present in the database before startup, or the cache will be empty.

## ConvertType
`ConvertType` controls how field values are formatted when **reading** data (search/get/copy and fetch APIs). It affects boolean/option fields and relational fields, and it also controls whether some relations are expanded or represented as references.

### ModelReference Structure
When relational values are expanded or returned as references, they use `ModelReference`.

Fields:
- `id`: related row id.
- `displayName`: related row display name.

### Values
1. `ORIGINAL` - returns the raw database value without formatting. Typical use cases: diagnostics or retrieving original ciphertext before decryption.
2. `TYPE_CAST` (default in `FlexQuery`) - standard conversion based on fieldFype (for example, numeric/string/boolean/json type casting). Computed fields and dynamic cascaded fields are evaluated. Relational fields are not expanded to display values by default.
3. `DISPLAY` - converts Boolean/Option/MultiOption and ManyToOne/OneToOne fields into display values (human-readable strings). OneToMany/ManyToMany default expansion (when no SubQuery is provided) returns display values.
4. `REFERENCE` - converts Option/MultiOption and ManyToOne/OneToOne fields into reference objects (`OptionReference` for option fields and `ModelReference` for relational fields). OneToMany/ManyToMany default expansion (when no SubQuery is provided) returns a list of `ModelReference` objects.

### Defaults and API behavior
1. `FlexQuery` defaults to `TYPE_CAST` if not explicitly set.
2. Web APIs (`/searchList`, `/searchPage`, `/getById`, `/getByIds`, etc.) typically set `ConvertType.REFERENCE` to return references for relational fields.
3. For OneToMany/ManyToMany fields: 
   - If a SubQuery is provided, related rows are expanded according to SubQuery fields/filters. 
   - If no SubQuery is provided and `convertType` is `DISPLAY` or `REFERENCE`, OneToMany/ManyToMany returns a list of display strings.
   - If no SubQuery is provided and `convertType` is `REFERENCE`, OneToMany/ManyToMany returns a list of `ModelReference` objects.

## QueryParams and SubQuery
`QueryParams` is the request body for `/searchPage`. It describes fields, filters, sorting, aggregation, paging, and relational expansions.

Key fields in `QueryParams`:
- `fields`: list of model fields to return. For relational fields, this controls which related fields are included when the relation is expanded (either by `ConvertType` or SubQuery). For ManyToOne/OneToOne, display name fields are always included in addition to `fields`. For OneToMany/ManyToMany, the related field used for grouping is also included.
- `filters`: list of filter conditions. Each condition is a list in the format `[field, operator, value]` (e.g., `["status", "=", "ACTIVE"]`).
- `orders`: list of sorting rules. Each rule is a list in the format `[field, direction]` (e.g., `["createdTime", "DESC"]`). Alternatively, `orders` can be a string like `"createdTime DESC, name ASC"`.
- `aggFunctions`: list of aggregation functions to apply (e.g., `["SUM(amount)", "COUNT(id)"]`).
- `pageNumber`: page number for pagination (starting from 1).
- `pageSize`: number of records per page.
- `groupBy`: list of fields to group by.
- `splitBy`: field to split results by (for split queries).
- `summary`: boolean flag to indicate whether to return summary data (e.g., totals) for the query.
- `effectiveDate`: date for evaluating effective-dated data.
- `subQueries`: map of relational field names to `SubQuery` objects, defining how to expand those relations.

Example request (basic paging + subQueries):
```json
{
  "fields": ["id", "name", "deptId", "projects"],
  "filters": ["status", "=", "ACTIVE"],
  "orders": [["createdTime", "DESC"]],
  "pageNumber": 1,
  "pageSize": 20,
  "subQueries": {
    "deptId": { "fields": ["id", "name"] },
    "projects": { "fields": ["id", "name"], "orders": [["createTime", "DESC"]], "topN": 3 }
  }
}
```

`SubQuery` is used inside `subQueries` to define how a relational field is expanded.

SubQuery fields:
1. `fields` - related model fields to return. For ManyToOne/OneToOne, display name fields are always included in addition to `fields`.
2. `filters` - filter conditions applied to the related model.
3. `orders` - sort rules for the related model.
4. `count` - when `true`, returns the count of related rows for OneToMany/ManyToMany instead of rows.
5. `topN` - OneToMany only; requires `orders`, returns top N related rows per parent.
6. `subQueries` - nested expansions for the related model.

Notes:
1. `QueryParams` does not expose `convertType`. The web layer sets `ConvertType.REFERENCE` by default. To use other `ConvertType` values, call the service layer with `FlexQuery` directly.
2. Filters and orders follow the same formats as top-level `filters` and `orders` (`Filters` list format and `Orders` list or string format).

## Return Values by ConvertType, QueryParams, SubQuery
Unless stated otherwise, the descriptions below refer to service-level `FlexQuery` behavior. Web APIs using `QueryParams` default to `ConvertType.REFERENCE`, so they follow the `REFERENCE` column when no SubQuery is specified.

### Boolean
| ConvertType | Return value |
| --- | --- |
| `ORIGINAL` | Raw stored value (no formatting). |
| `TYPE_CAST` | Boolean `true/false`. |
| `DISPLAY` | Boolean display name from the boolean option set (if configured); otherwise same as `TYPE_CAST`. |
| `REFERENCE` | Boolean `true/false` (no reference object for boolean). |

### Option / MultiOption
| ConvertType | Option (single) | MultiOption |
| --- | --- | --- |
| `ORIGINAL` | Raw stored item code. | Raw stored string (comma-separated). |
| `TYPE_CAST` | Item code string. | `List<String>` of item codes. |
| `DISPLAY` | Item display name. | Comma-joined display names (string). |
| `REFERENCE` | `OptionReference` (`itemCode`, `label`, `itemTone`, `itemIcon`). | `List<OptionReference>`. |

### ManyToOne / OneToOne
1. No SubQuery:
| ConvertType | Return value |
| --- | --- |
| `ORIGINAL` | Raw related id (no expansion). |
| `TYPE_CAST` | Related id (formatted by type). |
| `DISPLAY` | Related display name (string). |
| `REFERENCE` | `ModelReference` (`id`, `displayName`). |

2. With SubQuery:
Return value is always a related **row map** instead of a display name or `ModelReference`. The map is built using the same `convertType` (so nested option/relational fields follow that `convertType`). The returned fields include the related model `id`, all display-name fields, and any fields specified in `SubQuery.fields`.

### OneToMany / ManyToMany
1. Not in `QueryParams.fields` and no SubQuery
For OneToMany/ManyToMany fields, they would not be appear in the response if not specified in `QueryParams.fields` nor in `subQueries`.

2. In `QueryParams.fields`, no SubQuery:
| ConvertType | OneToMany | ManyToMany |
| --- | --- | --- |
| `TYPE_CAST` | `List<Map>` of related model rows (all stored fields). | `List<Map>` of related model rows (all stored fields). |
| `DISPLAY` | `List<Map>` containing `displayName` plus display fields and id (and the related field used for grouping). | `List<String>` of display names. |
| `REFERENCE` | `List<Map>` containing `displayName` plus display fields and id (and the related field used for grouping). | `List<ModelReference>`. |

3. With SubQuery:
1. If `count = true`, return an integer count per parent row. SubQuery `filters` are applied to the count.
2. Otherwise return related rows as `List<Map>`.
3. If `SubQuery.fields` is empty, all stored fields of the related model are returned by default.
4. If `SubQuery.fields` is specified, those fields are returned, and `id` is automatically included. For OneToMany, the related field used for grouping is also present.
5. Nested `subQueries` are applied recursively to the related model.

# Field Submit (Option/File/XToOne)
The formats below apply to create/update APIs (`createOne`, `createList`, `updateOne`, `updateList`, and variants).

## Option / MultiOption
Submit format:
1. `Option` (single): option item code string.
2. `MultiOption` (multiple): option item code list (recommended), or comma-separated string.

Examples:
```json
{
  "status": "Active",
  "tags": ["A", "B", "C"]
}
```

Clear semantics:
1. `Option`: set field to `null` (if field is not required).
2. `MultiOption`: set to `[]`, or `null` (if field is not required).

## File / MultiFile
Submit format:
1. `File` (single): uploaded `fileId`.
2. `MultiFile` (multiple): `fileId` list.

Examples:
```json
{
  "avatar": "1001",
  "attachments": ["2001", "2002", "2003"]
}
```

Clear semantics:
1. `File`: set field to `null` (if field is not required).
2. `MultiFile`: set to `[]`, or `null` (if field is not required).

## ManyToOne / OneToOne
Submit format:
1. Submit related row id directly.
2. Do not submit a nested object (`{id, displayName}`) as input value.

Examples:
```json
{
  "deptId": "3001",
  "ownerId": "4001"
}
```

Clear semantics:
1. Set field to `null` (if field is not required) to unlink relation.

Validation notes:
1. Related ids are formatted according to the related model id type before persistence.
2. If the field is required, setting `null` will be rejected.

## API Return by Field Type
This section describes model API return values (`/getById`, `/getByIds`, `/searchList`, `/searchPage`) by field type.
By default, ModelController uses `ConvertType.REFERENCE`.

### Option / MultiOption
Default API return (`REFERENCE`):
1. `Option` -> `OptionReference` object:
```json
{ "itemCode": "Active", "label": "Active", "itemTone": "success", "itemIcon": "check" }
```
2. `MultiOption` -> `List<OptionReference>`:
```json
[
  { "itemCode": "A", "label": "Tag A", "itemTone": "", "itemIcon": "" },
  { "itemCode": "B", "label": "Tag B", "itemTone": "", "itemIcon": "" }
]
```

### File / MultiFile
Default API return (`REFERENCE`):
1. `File` -> `FileInfo` object.
2. `MultiFile` -> `List<FileInfo>`.

Typical `FileInfo` fields include:
1. `fileId`
2. `fileName`
3. `size`
4. `url`

Example:
```json
{
  "avatar": { "fileId": 1001, "fileName": "a.png", "url": "..." },
  "attachments": [
    { "fileId": 2001, "fileName": "doc.pdf", "url": "..." },
    { "fileId": 2002, "fileName": "spec.docx", "url": "..." }
  ]
}
```

### ManyToOne / OneToOne
Default API return (`REFERENCE`, no SubQuery):
1. `ManyToOne` / `OneToOne` -> `ModelReference` object:
```json
{ "id": 3001, "displayName": "Engineering" }
```

With `SubQuery`:
1. Return value becomes a row map of the related model (not `ModelReference`).
2. Returned map includes related model `id`, displayName fields, and `SubQuery.fields`.

### ManyToMany / OneToMany
Default API return (`REFERENCE`, no SubQuery):
1. `ManyToMany` -> `List<ModelReference>`.
2. `OneToMany` -> `List<Map>` (related rows containing `id`, `displayName`, and grouping relatedField).

Examples:
```json
{
  "projects": [
    { "id": 5001, "displayName": "Project Alpha" },
    { "id": 5002, "displayName": "Project Beta" }
  ],
  "orderLines": [
    { "id": 7001, "displayName": "Line A", "orderId": 9001 },
    { "id": 7002, "displayName": "Line B", "orderId": 9001 }
  ]
}
```

With `SubQuery`:
1. `ManyToMany` / `OneToMany` both return `List<Map>` of related rows.
2. If `count=true`, return integer count instead of rows.

## XToMany API Submit

`OneToMany` and `ManyToMany` fields support both full submit and incremental patch submit in create/update APIs
(`createOne`, `createList`, `updateOne`, `updateList`, and `*AndFetch` variants).

The backend distinguishes mode by field value type:
1. `List` -> full submit mode (existing behavior, backend infers add/update/delete by diff)
2. `Object(Map)` -> patch submit mode (execute operations by patch keys directly)

`PatchType` Options:
- OneToMany fields: `Create`、 `Update`、`Delete`；
- ManyToMany fields: `Add`、`Remove`；

### OneToMany Submit
Field value supports:
1. Full submit: `[{row1}, {row2}]`. If the field value is `[]` in update API,, clear the history record.
2. Patch submit, `PatchType` as the key:
```json
{
  "Create": [{ "name": "new row" }],
  "Update": [{ "id": 101, "name": "changed" }],
  "Delete": [102, 103]
}
```

Rules:
1. `Create`/`Update` values must be row list (`List<Map>` or object list convertible to map).
2. `Delete` value must be id list.
3. In create main model data scene, only `Create` is allowed; `Update` and `Delete` are rejected.
4. In update main model data scene, `Update`/`Delete` ids must belong to the current parent row.

### ManyToMany Submit
Field value supports:
1. Full submit: `[id1, id2, id3]`. If the field value is `[]` in update API, clear the history record.
2. Patch submit, `PatchType` as the key:
```json
{
  "Add": [1, 2, 3],
  "Remove": [4, 5]
}
```

Rules:
1. `Add` and `Remove` values must be id list (or object list with readable `id` field).
2. In create main model data scene, only `Add` is allowed; `Remove` is rejected.
3. In update main model data scene:
   - `Add` adds only non-existing relationships.
   - `Remove` deletes only existing relationships.

### Compatibility and Validation
1. Full submit mode remains compatible with existing infer logic.
2. Patch keys are case-insensitive and support both enum-style and display-style names:
   - OneToMany: `CREATE/UPDATE/DELETE` or `Create/Update/Delete`
   - ManyToMany: `ADD/REMOVE` or `Add/Remove`
3. Unknown patch key or non-list patch value will fail fast with parameter validation error.

## Service-to-Service RPC

### When to use it

When an entity model is owned by a different app (e.g. `Order` lives in the
`payments` app but is read from this app), the framework redirects ORM calls for
that model over HTTP to the owning app. Routing is automatic and keyed on the
model's `appCode` (projected from `sys_model.app_code`): when a model's
`appCode` differs from this runtime's `system.app-code`, the call is routed to
the owning app; otherwise it runs locally. There is no per-model routing flag to
set. Your code keeps calling `JdbcService` as if the model were local.

This is not a general-purpose RPC mechanism — only `JdbcService` methods on
metadata-driven models are RPC-able. For arbitrary cross-service calls, use a
plain `RestClient`.

### Quick start

1. Nothing to annotate — a model's owning app is its `appCode`, stamped
   server-side. The caller only needs to know which apps it routes to.

2. Configure the **caller's** `application.yml`, keyed by the **owning app's
   `appCode`** (its `system.app-code`):

   ```yaml
   rpc:
     enable: true
     services:
       payments:                     # key = owning app's appCode
         api-url: http://payments.internal:8080
         api-key: <shared>
         api-secret: <shared>
   ```

3. Configure the **receiver's** `application.yml` (only this line is needed):

   ```yaml
   rpc:
     enable: true
   ```

4. Call `JdbcService` normally — the framework auto-routes:

   ```java
   List<Map<String, Object>> rows = jdbcService.getList("Order", filters);
   // model's appCode == this runtime's system.app-code → local;
   // differs → HTTP POST to the owning app
   ```

**How it works**: `SwitchServiceAspect` intercepts `JdbcService` calls; when the
model's `appCode` differs from this runtime's `system.app-code`, the call is
POSTed to `/rpc/{modelName}/{methodName}` on the owning app (resolved via
`rpc.services.<appCode>`). The caller's request `Context` (tenant / user /
language) is propagated so the remote invocation runs with the same identity.

### Configuration

#### Minimal (caller)

```yaml
rpc:
  enable: true
  services:
    payments:                       # key = owning app's appCode (system.app-code)
      api-url: http://payments.internal:8080
      api-key: <shared>
      api-secret: <shared>
```

Receiver only needs `rpc.enable: true`.

With this minimal config you get framework defaults: 3 retries with exponential
backoff (300 ms → 3 s cap), per-host circuit breaker, 3 s connect / 30 s read
timeout.

#### Full (caller, custom resilience policies)

```yaml
rpc:
  enable: true
  services:
    payments:
      api-url: http://payments.internal:8080
      api-key: <shared>
      api-secret: <shared>
    fast-dfs:
      api-url: http://fast-dfs.internal:8888
      api-key: <shared>
      api-secret: <shared>

resilience4j:
  retry:
    instances:
      softa-rpc:                    # instance name is fixed — applies to all RPC targets
        max-attempts: 3
        wait-duration: 300ms
        enable-exponential-backoff: true
        exponential-backoff-multiplier: 2
        exponential-max-wait-duration: 3s
        retry-exceptions:
          - io.softa.framework.web.resilience.TransientHttpException
          - java.io.IOException
  circuitbreaker:
    instances:
      softa-rpc:
        sliding-window-size: 20
        failure-rate-threshold: 50
        wait-duration-in-open-state: 15s
```

#### Field reference

| Field | Required | Description |
|---|---|---|
| `rpc.enable` | yes | Gates the dispatcher (caller) and the `/rpc` endpoint (receiver) |
| `rpc.services.<appCode>.api-url` | yes (caller) | Base URL; framework appends `/rpc/{model}/{method}` |
| `rpc.services.<appCode>.api-key` | yes (caller) | Sent as `X-Api-Key` header |
| `rpc.services.<appCode>.api-secret` | yes (caller) | Sent as `X-Api-Secret` header |
| `resilience4j.retry.instances.softa-rpc.*` | no | Overrides the default retry policy |
| `resilience4j.circuitbreaker.instances.softa-rpc.*` | no | Overrides the default circuit-breaker policy |

### Constraints

- **Single endpoint shape**: only methods on `JdbcServiceImpl` are RPC-targetable,
  and the first argument must be `String modelName`. Custom service methods are
  not transparently RPC-able.
- **Java serialization on the wire**: all method arguments and return values
  must implement `Serializable`. Cross-language consumers are not supported.
- **Static service registry**: appCode → URL is resolved from YAML only;
  no service discovery. Switch per environment via `application-{profile}.yml`.
- **One Resilience4j policy for all targets**: every RPC call shares the
  `softa-rpc` retry + circuit-breaker instance — you can't tune SLAs per target.
- **System models never redirect**: `sys_*` catalog rows always serve locally,
  regardless of `appCode`. Prevents circular routing during bootstrap.

### Failure handling

- RPC failures (non-success `ApiResponse`, null body, or deserialization error)
  surface as `io.softa.framework.base.exception.ExternalException`.
- HTTP-layer errors (status codes, network timeouts) bubble up as
  `RestClientResponseException` after being logged with target URL + status +
  body.
- Retry / circuit-breaker activity is exposed at `/actuator/retries` and
  `/actuator/circuitbreakers` (via the Resilience4j Spring Boot starter).
