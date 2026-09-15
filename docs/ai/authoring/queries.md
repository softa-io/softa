# Reading & Writing Data

Part of the [Softa app authoring guide](../README.md). How to query, and the
exact JSON shapes for create/update payloads and API responses. Pairs with
[controllers-services.md](controllers-services.md).

Two ways to read:
- **Over REST** — POST `QueryParams` to `/{Model}/searchPage` or `/searchList`.
- **In service code** — call `searchList(Filters)` / `searchPage(FlexQuery, Page)`.

---

## Querying over REST — `QueryParams`

`QueryParams` is the request body for `/{Model}/searchPage` (and `/searchList`):

| Field | Meaning |
|---|---|
| `fields` | which fields to return; for relations, which related fields to include when expanded |
| `filters` | conditions, each `[field, operator, value]` |
| `orders` | sort rules, each `[field, direction]` — or a string `"createdTime DESC, name ASC"` |
| `aggFunctions` | e.g. `["SUM(amount)", "COUNT(id)"]` |
| `groupBy` | fields to group by |
| `pageNumber` / `pageSize` | pagination (page starts at 1) |
| `subQueries` | map of relational field → `SubQuery`, to expand relations |
| `effectiveDate` | as-of date for timeline (effective-dated) models |

```json
{
  "fields": ["id", "name", "deptId", "projects"],
  "filters": ["status", "=", "ACTIVE"],
  "orders": [["createdTime", "DESC"]],
  "pageNumber": 1,
  "pageSize": 20,
  "subQueries": {
    "deptId":   { "fields": ["id", "name"] },
    "projects": { "fields": ["id", "name"], "orders": [["createTime", "DESC"]], "topN": 3 }
  }
}
```

### Filters format
Each condition is `[field, operator, value]`, e.g. `["status", "=", "ACTIVE"]`.
Combine multiple in the `filters` list. (In service code the same is expressed
with the `Filters` builder.)

### Filters as an expression

The same condition can be written as an expression instead of nested lists — `status = "ACTIVE"`,
`status = "ACTIVE" AND grade >= 6` — which is the form `@Field(requiredWhen / hiddenWhen /
readonlyWhen / invalidWhen)` should use, because in a Java text block it needs no escapes.
`Filters.of` picks the form by the first character: a leading `[` is the list form, anything else is
parsed as an expression, and both produce the same tree.

The grammar in full — operators, value forms, `AND` / `OR` precedence, and the two shapes it refuses —
is in [softa-orm's README](../../../framework/softa-orm/README.md) under Field constraints.

### Orders format
List form `[["createdTime", "DESC"], ["name", "ASC"]]` or string form
`"createdTime DESC, name ASC"`.

### Expanding relations — `SubQuery`
Inside `subQueries`, keyed by the relational field name:

| SubQuery field | Meaning |
|---|---|
| `fields` | related fields to return (id + display-name fields always included) |
| `filters` / `orders` | applied to the related rows |
| `count: true` | return a count of related rows instead of the rows (OneToMany/ManyToMany) |
| `topN` | OneToMany only; requires `orders`; top N related rows per parent |
| `subQueries` | nested expansion |

---

## Querying in service code — `Filters` / `FlexQuery`

```java
// simple: all active employees
List<EmpInfo> active = empInfoService.searchList(Filters.of("status", "=", "ACTIVE"));

// paginated + shaped: use FlexQuery
FlexQuery q = new FlexQuery()
        .setFields(List.of("id", "name", "deptId"))
        .setFilters(Filters.of("status", "=", "ACTIVE"))
        .setOrders(Orders.of("createdTime", "DESC"));
Page<EmpInfo> page = empInfoService.searchPage(q, new Page<>(1, 20));
```

`FlexQuery` is the service-level equivalent of `QueryParams`; it additionally
lets you set `ConvertType` (see below), which `QueryParams` does not expose.

---

## ConvertType — how values come back

Controls formatting of option / boolean / relational fields on read.

| ConvertType | Option field | ManyToOne / OneToOne |
|---|---|---|
| `ORIGINAL` | raw stored code | raw related id |
| `TYPE_CAST` (FlexQuery default) | item code string | related id |
| `DISPLAY` | item display name | related display name |
| `REFERENCE` (**web API default**) | `OptionReference` `{itemCode,label,itemTone,itemIcon}` | `ModelReference` `{id,displayName}` |

Web endpoints (`/searchPage`, `/getById`, …) default to `REFERENCE`. To get other
behavior, query through the service layer with `FlexQuery.setConvertType(...)`.

---

## Submitting data — create/update payloads

These apply to `createOne` / `createList` / `updateOne` / `updateList` and their
`*AndFetch` variants.

**Option / MultiOption** — submit the item **code(s)**:
```json
{ "status": "ACTIVE", "tags": ["A", "B"] }        // MultiOption also accepts a comma string
```

**File / MultiFile** — submit the uploaded file **id(s)**:
```json
{ "avatar": "1001", "attachments": ["2001", "2002"] }
```

**ManyToOne / OneToOne** — submit the related **id** directly (not a nested object):
```json
{ "deptId": "3001" }
```
Set a non-required relation to `null` to unlink.

**OneToMany / ManyToMany** — either full-submit a list, or patch:
```json
// OneToMany patch
{ "orderLines": { "Create": [{ "name": "new" }], "Update": [{ "id": 101, "name": "x" }], "Delete": [102] } }

// ManyToMany patch
{ "tags": { "Add": [1, 2], "Remove": [4] } }
```
A `List` value means full-submit (backend diffs add/update/delete); an object
keyed by patch type means incremental. On create, only `Create` / `Add` are
allowed.

---

## Reading data — response shapes (default `REFERENCE`)

| Field type | Returned as |
|---|---|
| Option | `{ "itemCode": "ACTIVE", "label": "Active", "itemTone": "success", "itemIcon": "check" }` |
| MultiOption | list of the above |
| File | `FileInfo` `{ "fileId", "fileName", "size", "url" }` |
| MultiFile | list of `FileInfo` |
| ManyToOne / OneToOne | `{ "id": 3001, "displayName": "Engineering" }` |
| OneToMany | `List<Map>` with `id`, `displayName`, and the grouping FK |
| ManyToMany | `List<{id, displayName}>` |

With a `SubQuery`, a relation returns the full related **row map** (using the
same `ConvertType`) instead of a reference.

`FileInfo.url` is a ready-to-use download URL — see the file-starter reference
([feature-starters.md](feature-starters.md)) for upload/storage mechanics.
