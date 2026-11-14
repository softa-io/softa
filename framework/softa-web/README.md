# Softa Web

## Request Context and MDC

`ContextScopeFilter` (order `HIGHEST_PRECEDENCE`) resolves the caller and binds a `Context` for the whole filter chain, so anything downstream reads the request identity through `ContextHolder` rather than passing it around. The `Context` lives in a `ScopedValue`, which the logging system cannot see — so the same filter also mirrors three fields of it into SLF4J's **MDC** for the duration of the scope:

| MDC key | Source | Present when |
|---|---|---|
| `traceId` | `Context.traceId` — taken from the caller's trace header, or generated | always, inside a bound scope |
| `tenantId` | `Context.tenantId` | multi-tenant requests |
| `userId` | `Context.userId` | authenticated requests |

The keys are declared as constants in `MdcKeys` (softa-base) — reference them instead of repeating the literals. They are removed in a `finally`, so nothing leaks past the request onto a reused thread.

What this buys, with no application code involved: **every log line written inside a request carries the request identity**. Turn on structured logging (`logging.structured.format.console=ecs`) and they become queryable JSON fields, so a log platform can filter by tenant or follow one `traceId` through a request; any MDC-aware integration picks them up too (`sentry-starter` attaches them to error events, which is what lets an issue in Sentry be cross-referenced with the matching log lines).

Requests that skip context binding have no MDC either — `IdentifyType.NONE` and `OPENAPI` endpoints run outside a bound scope by design.

## Error Logs

An API call that fails is logged by `WebExceptionHandler` together with the request it failed on (`RequestInfoHandler`): the URL and query string, the user, the traceId, the client IP and the method parameters `ApiExceptionAspect` collected. An exception whose log level is WARN (`BusinessException` among them) is logged at WARN, any other at ERROR. With `sentry-starter`, an ERROR line becomes a Sentry event and the request's earlier lines its breadcrumbs, text and all — `sentry.send-default-pii: false` does not reach the text of a log line.

Credentials are masked in that text before it is written (`SecretMasker`). A value becomes `***` when the name it is sent under — a method parameter, a query parameter, or a JSON member at any depth, whether in a parameter written out as JSON or in the request body — is one of:

`password` `newPassword` `currentPassword` `oldPassword` `jdbcPassword` `verificationCode` `codeVerifier` `ticket` `proof` `token` `authToken` `verificationToken` `secret` `apiKey` `apiSecret` `privateKey`

Names are compared whole and ignoring case, so `token` is masked and `tokenType` is not; a null or empty value is kept. `code` is deliberately not on the list: it names business codes (a role, template or sequence code) far more often than one-time ones, so a verification code is masked when it is sent as `verificationCode`. A new credential field takes one of these names, or adds its own to `SecretMasker`. Only credentials are masked: personal data in a request still reaches the line, and exception messages are written as they are.

## Record access

Two model endpoints tell a form what to render before it renders it; both answer only about the caller's
own access, never return business data, and are answered by `PermissionService` (all open when no
permission module is present):

| Endpoint | Answers |
|---|---|
| `POST /{model}/getRecordAccess` (body: ids) | per record: the sensitive field sets hidden on it, those visible but read-only, the same two answers by field (`hiddenFields` / `readonlyFields`, keyed by model), and which of `UPDATE` / `DELETE` the caller may perform |
| `GET /{model}/getCreateAccess` | the sensitive field sets a new record's form must not show, and their fields by model (`hiddenFields`) |

A detail form hides Edit / Delete and the sections of hidden sets from the answer, and renders the
sections of read-only sets read-only. The record's answer stands on its own: it overrides what the user
holds through some other role.

**A company picker offers the companies the write may name.** `POST /Company/searchName` with a
`sourceRecord` — the record the picker fills a field of — is narrowed to the company grants of the roles
holding that write: create when `sourceRecord.recordId` is empty, update otherwise
(`PermissionService.getGrantedCompanyIds`). The company list as the user reads it reaches the companies of
every role they hold, and a role with no company grant (the self-service role) reaches them all, so a form
picking a signing entity or a cost centre's company would offer companies the save then refuses. A search
with no `sourceRecord` — a list filter, a report — is left as it reads. A write granted no company offers
none: every company the picker could list would be refused. On an update, only the holding roles reaching
that record count.

**A department picker offers what the write may choose.** `POST /Department/searchName` and
`POST /Department/searchList` — the read a tree picker makes — take the same `sourceRecord`, and are narrowed to the
department grants of the roles holding that write (`PermissionService.getWritableDepartmentScope`). The list as
the user reads it answers for what they may view, which an exemption from the grant opens in full. Only where the
picked department places the record: a model with a `departmentId` of its own, or the department model itself. A
picker that names its field in `sourceRecord.fieldName` is narrowed only when that field places the record: the
`departmentId`, or a department's `parentId`.
## Field errors

A rejection that is about particular fields answers with HTTP 200 and code 400 as any rejected input does,
and adds `fieldErrors` (field name → sentence) to the body so a form can put each sentence on its control
instead of one toast. Two rejections carry it:

| Rejection | Raised by | `error` |
|---|---|---|
| A refused write | field constraints or a `ModelWriteValidator` (`WriteValidationException`) | `field: sentence; …`, fields by label |
| A request body failing its bean validation | `@Valid` on a controller argument, field or class-level constraints (`MethodArgumentNotValidException`) | `Validation Error: field: sentence; …` |

A field with more than one sentence keeps its first in `fieldErrors`; `error` still lists them all. Every
other error leaves `fieldErrors` out, so a client that does not read it sees the body it always did.

## Enum Request Parameters

Query, path and header parameters bind an enum by the value JSON uses for it — the `@JsonValue` field when the enum declares one, the constant name otherwise — so a parameter takes the same value that request bodies, responses and option items show: for `Status.ACTIVE("Active")`, send `?status=Active`. Matching is exact, a blank value binds as `null`, and a value naming no constant fails to bind. `BaseWebConfig.addFormatters` registers this in place of Spring's default binding, which matches the constant name only.

The constant name (`?status=ACTIVE`) still binds: when the conversion fails, Spring MVC falls back to looking the text up as a constant name. That fallback is Spring's, not part of the API — send the JSON value.

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

## SearchNameParams
`SearchNameParams` is the request body for `/searchName`: a relation field's candidates, each as `id` and `displayName` plus any `additionalFields`, one page at a time.

Key fields in `SearchNameParams`:
- `value`: the text typed, matched with `operator` (default `CONTAINS`) on `matchField` (default `searchName`, the model's search fields). Blank lists every candidate.
- `filters`: context filters from the relation field or the form, joined to the match with AND.
- `orders`: sorting rules. Without them the model's `defaultOrder` applies, and newest first (`id DESC`) on a model without one; either way the primary key ends the order (`sliceId` on a timeline model), so consecutive pages neither repeat nor skip a candidate.
- `additionalFields`: fields returned beside `id` and `displayName`.
- `pageNumber`: which page, counting from 1 (default 1).
- `pageSize`: candidates per page, 10 when omitted, at most `MAX_BATCH_SIZE`. It replaced `limitSize`, which the endpoint no longer reads.
- `effectiveDate`: the date effective-dated models are read on, default today.
- `sourceRecord`: the record being edited, for searches that depend on it.

Example request (the second page of active records matching "an"):
```json
{
  "value": "an",
  "filters": ["active", "=", true],
  "additionalFields": ["code"],
  "pageNumber": 2,
  "pageSize": 20
}
```

Notes:
1. The response is the page's rows as a list, not a `Page`: a name search reads a cursor page, in one stable order and with nothing counted, so a picker asks for the next page until one comes back short.
2. In code, `toFlexQuery()` builds the query (the match, filters, orders, additional fields, `ConvertType.REFERENCE`, the effective date) and `toPage()` the page; `ModelService.searchName(modelName, flexQuery, page)` takes both and returns the page's rows, the same list the endpoint answers with. A controller that serves its own `/searchName` to narrow the candidates adds its filters to `toFlexQuery()` and passes `toPage()` on unchanged, so its pages keep the same order.
3. A lookup that wants a known set rather than a picker's pages, such as the names of known ids, sends its `filters` with `pageSize` set to how many it expects.

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
4. `url` — a site-relative address with a signed key, not a storage link; see [File keys](#file-keys)

Example:
```json
{
  "avatar": { "fileId": 1001, "fileName": "a.png", "url": "/api/demo/file/open/1001.1797206400.G4CHYJkdKbgZABYFb9A1mw" },
  "attachments": [
    { "fileId": 2001, "fileName": "doc.pdf", "url": "/api/demo/file/open/2001.1797206400.qP3vXWm1lB0aZ7cYt5dE2g" },
    { "fileId": 2002, "fileName": "spec.docx", "url": "/api/demo/file/open/2002.1797206400.K9sLx2Rb0nVfQ6hTz1uW4A" }
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

## File keys

`FileInfo.url` — on every File / MultiFile field read, every upload, every generated file — is not a storage link. It is a site-relative address carrying a signed key (`FileOpenKeys`, softa-orm), made in `FileServiceImpl.convertToFileInfo` each time a file is returned:

| Request | `url` | Opens for | Valid |
|---|---|---|---|
| With a logged-in user (`Context.userId`) | `<context-path>/file/open/<key>` | that account only — its session must present the key | until UTC midnight of the issue day + 31 days (30–31 days; one address per file per day) |
| Without one: a public page under `/openapi/**`, a scheduled job, a flow nobody triggered | `<context-path>/openapi/file/<key>` | anyone holding it | 1 hour |

A key is `<fileId>.<exp>.<signature>`: the signature is HMAC-SHA256 over the file, the expiry and — for a user key — the account, cut to 128 bits and base64url-encoded. The account is signed in, not written out: opening recomputes the signature with the userId of the session presenting the key. Nothing is stored, nothing is encrypted, no Redis.

Opening (`FileController` `GET /file/open/{key}`, `PublicFileController` `GET /openapi/file/{key}`) checks the key and answers `302` to a storage link signed at that moment, valid `oss.url-expire-seconds` (default 300). `?download=1` asks for `Content-Disposition: attachment` with the file name; otherwise the file displays inline. Generated files (`uploadFromStream`: exports, import results, documents) carry `?download=1` already. Every answer is `Cache-Control: no-store` — a shared cache holding one account's `302` would hand the file to anyone. Refusals are one line of plain text, since these addresses are opened by img, iframe and new tabs: `404` malformed key or missing file, `403` a key issued to another account (or of the other kind), `410` expired. A `/file/open/` address reached with no usable session gets `401` from `ContextScopeFilter`, in plain text too — not the HTTP 200 JSON envelope every other call gets, which a download would save as the file.

Opening does not re-check access to the record the file hangs on: a key only comes out of a read that was authorized already, under the record's scope. A revoked permission or a removed attachment therefore stops keys already handed out when they expire — at most 31 days — not at once. Rebinding an account to another person (Unbind & Re-invite) revokes the previous person's sessions, which their keys need.

Configuration — required; startup fails without it:

```yaml
file:
  open-key-secret: ${FILE_OPEN_KEY_SECRET:}   # at least 32 characters: openssl rand -base64 48
```

Every instance must use the same secret: a key one instance signed has to open on any other, rolling deploys included. Changing it invalidates every key handed out — a page refresh issues new ones.

With `permission-starter`, the endpoint gate cannot tell a model from `/file/open/**` (the same reason as `/file/uploadFileTo*`): list it in `permission.authenticated-bypass-patterns` — the key is the check. `/openapi/file/**` must be reachable without a session: it is under `/openapi/**`, which `IdentityResolver` binds no context for; list it (or `/openapi/**`) in `permission.public-uri-patterns`.

Rules for code that handles files:

- Caches, the database, flow variables and mails hold file ids, never addresses. The address is made when the data is returned to a client; an address kept anywhere outlives what signed it.
- Backend code never reads `FileInfo.url`. Read the content with `FileService.downloadStream(fileId)`; attach a file to a mail by its `FileInfo` (the transport streams it by `fileId`); hand a file to an external system that fetches by URL with `FileService.getStorageLink(fileId, download)` — signed at call time, gone within minutes, never stored.
- To let a person open a file later (a mail, an SMS, a notification), attach it or link to the record's page; the person signs in and reads the record, which hands them their own key.

Clients: a browser on the same origin sends the session cookie by itself. The mobile APP sends `X-Session-Id` with image and file requests; for a viewer that cannot send headers (the system browser, a third-party PDF viewer), it requests the address with the header itself and hands the `302`'s `Location` — a short storage link — to the viewer.

## APP Version Check

For an application with a mobile APP: `AppVersionGate` (order `HIGHEST_PRECEDENCE + 1`, right after the identity filter) stops an APP below the minimum supported version, and `GET /app/version?platform=ios` tells the APP which versions are supported and where to get a new one. Nothing is refused until a version is configured, so an application without an APP needs no configuration.

```yaml
app:
  versions:
    ios:
      min-version: 1.0.0      # below it, every signed-in endpoint answers UPGRADE_REQUIRED
      latest-version: 1.2.0   # below it, the APP offers an upgrade the person may postpone
      store-url: https://apps.apple.com/...   # https, itms-apps or market; see below
      release-notes: ...
  # Signed-in endpoints an old APP may still call. Signing out (/UserAccount/logout) is always open.
  upgrade-exempt-paths:
    - DELETE /app/devices/*   # "[METHOD] /path pattern"; no method means any; excludes the context path
```

The refusal is the usual error body (HTTP 200) with `errorCode` `UPGRADE_REQUIRED`.

- **Who is checked:** requests with both `X-App-Platform` and `X-App-Version` (`SessionStore.APP_PLATFORM_HEADER` / `APP_VERSION_HEADER`) on a signed-in endpoint, on a platform with a `min-version`. A request with only one header has nothing to compare and passes, though `SessionStore.isAppRequest` calls it an APP's for session handling. Web and H5 send neither. The headers are self-reported: this steers an honest old APP to the upgrade screen and is no security boundary.
- **Versions** compare by number: `1.10.0` is newer than `1.9.3`, a missing part counts as 0, a leading `v` is ignored.
- **`store-url`** is opened by the APP only when it starts with `https`, `itms-apps` (the iOS App Store) or `market` (the generic Android store form, `market://details?id=<package>`, which phones from Chinese vendors open in their own store). Any other form — a vendor's private store scheme, an `intent:` URL — is not opened. Left empty, the upgrade button cannot be tapped.
- **`/app/version` has to be declared anonymous by the application**, since an APP too old to do anything else must still reach it. Like any public endpoint that takes both layers, and a profile's list replaces the base's rather than adding to it:

```yaml
web:
  identity:
    additional-anonymous-patterns: /app/version
permission:                   # with permission-starter
  public-uri-patterns:
    - /app/version
```

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
