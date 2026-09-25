# Softa ORM

## Metadata Annotations

> **Requires `metadata-starter`** as a dependency of your app for these
> annotations to take effect. `softa-orm` defines the annotations;
> `metadata-starter` contains the scanner and checker that read them and
> reconcile with `sys_*`. Without `metadata-starter` the annotations exist
> on your classes but no scanner consumes them — `sys_*` rows are never
> written and no DDL is generated.

Softa describes models, fields, option sets, option items, and indexes
through Java annotations on the entity classes. A boot-time scanner reads
these annotations, reconciles them with the `sys_*` catalog tables managed
by `metadata-starter`, and (for packages in `scanner-scope`) applies the matching DDL.

**Five annotations**, all in `io.softa.framework.orm.annotation`:

| Annotation | Target | `sys_*` table written | Purpose |
|---|---|---|---|
| `@Model` | class | `sys_model` | Describes an entity (table, business key, multi-tenancy, soft delete, etc.) |
| `@Field` | field | `sys_field` | Describes a column (label, type, length, required, relations, etc.) |
| `@OptionSet` | enum class | `sys_option_set` | Marks an enum as a managed option set |
| `@OptionItem` | enum constant | `sys_option_item` | Per-constant display attributes |
| `@Index` | class (`@Repeatable`) | `sys_model_index` | Declares a database index |

```java
@Data
@EqualsAndHashCode(callSuper = true)
@Model(
    label = "Customer",
    businessKey = {"code"},
    description = "Customer master"
)
@Index(indexName = "uk_customer_code", fields = {"code"}, unique = true)
@Index(fields = {"status", "createdTime"})
public class Customer extends AuditableModel {

    @Field(label = "ID")
    private Long id;

    @Field(label = "Customer Code", required = true, length = 32)
    private String code;

    @Field(label = "Customer Tier")
    private CustomerTier tier;   // enum → FieldType.OPTION (inferred)
}

@OptionSet(label = "Customer Tier")
public enum CustomerTier {
    @OptionItem(label = "VIP Gold") GOLD("g"),   // explicit: "VIP Gold" ≠ humanize("GOLD")
    SILVER("s");                                 // bare: label defaults to humanize("SILVER") = "Silver"

    @JsonValue private final String code;       // itemCode = @JsonValue
    CustomerTier(String code) { this.code = code; }
}
```

### Inference rules (no annotation needed)

| Concept | Derived from | Override |
|---|---|---|
| `modelName` | class simple name | — (no override) |
| `fieldName` | Java field name | — (no override) |
| `optionSetCode` | enum class simple name | — (no override) |
| `itemCode` | `@JsonValue` field value (fallback `enum.name()`) | — (no override) |
| `tableName` | `snake_case(modelName)` | `@Model.tableName` |
| `columnName` | `snake_case(fieldName)` | `@Field.columnName` |
| `fieldType` | Java type via `TypeInference` (e.g. `String`→`STRING`, enum→`OPTION`, `List<enum>`→`MULTI_OPTION`, `@Model` POJO→`MANY_TO_ONE`, `DTOFieldObject` POJO→`DTO`) | `@Field.fieldType = FieldType.X` (single value, no braces); **`OPTION` / `MULTI_OPTION` cannot be written explicitly**; `TEXT` (unbounded long text) is **never inferred** — declare it explicitly on a `String` field |
| index `indexName` | `idx_<table>_<col>...` / `uk_<table>_<col>...` for unique; **globally unique** (boot-enforced), ≤60 chars | `@Index.indexName` |

### `@Model` ↔ `SysModel`

| `@Model` attribute | Type | Default | `SysModel` column | Notes |
|---|---|---|---|---|
| (class simple name) | — | — | `modelName` | inferred, no override |
| `label` | String | `""` | `label` | empty → humanized class name (`DeptInfo`→"Dept Info"); i18n translations override by id |
| `tableName` | String | `""` | `tableName` | empty → `snake_case(modelName)` |
| `description` | String | `""` | `description` | **≤512 chars**, parse-time enforced (catalog column width); concise user-facing summary — design notes go in Javadoc |
| `displayName` | String[] | `{}` | `displayName` | list-display defaults |
| `searchName` | String[] | `{}` | `searchName` | search-field defaults |
| `defaultOrder` | String[] | `{}` | `defaultOrder` | e.g. `"createdTime:desc"` |
| `softDelete` | boolean | `false` | `softDelete` | requires a `deleted` field on the class; every read appends `deleted = false` (bypass via `FilterControl.bypassSoftDelete()`), and the starting value `false` is materialized into `sys_field.default_value` so the column DDL carries `DEFAULT FALSE` |
| `activeControl` | boolean | `false` | `activeControl` | requires an `active` field on the class; every read appends `active = true` unless the caller's own filters name `active` (or `FilterControl.bypassActiveControl()` is set), so disabling retires a row from reads without deleting it; the starting value `true` is materialized into `sys_field.default_value` so the column DDL carries `DEFAULT TRUE` |
| `timeline` | boolean | `false` | `timeline` | effective-dated rows (see Timeline Model) |
| `idStrategy` | `IdStrategy` | `DB_AUTO_ID` | `idStrategy` | |
| `storageType` | `StorageType` | `RDBMS` | `storageType` | |
| `versionLock` | boolean | `false` | `versionLock` | requires a `version` field on the class; the field is readonly (framework-managed), stamped onto every insert, and its starting value is materialized into `sys_field.default_value` (`0` unless the field declares `defaultValue`) so the column DDL carries `DEFAULT 0` |
| `multiTenant` | boolean | `false` | `multiTenant` | requires a `tenantId` field on the class |
| `multiCountry` | boolean | `false` | `multiCountry` | rows are partitioned by country; reads narrow to the countries the caller works in (see [Request-scoped narrowing](#request-scoped-narrowing)) |
| `multiCompany` | boolean | `false` | `multiCompany` | rows belong to one company; the anchor field the permission grant bounds every read by (see [Request-scoped narrowing](#request-scoped-narrowing)) |
| `copyable` | boolean | `true` | `copyable` | `false` ⇒ copy APIs reject the model; UI hides Duplicate |
| `projection` | boolean | `false` | `projection` | `true` ⇒ read-only model over a table it does NOT own (another model's table, or one created externally, e.g. by a BI pipeline). No DDL is ever generated for it (`sys_*` rows still reconcile); write APIs reject it; `@Index` on it is boot-rejected; RDBMS only. Every non-projection RDBMS model owns its table exclusively — two owners on one `tableName` fail at boot |
| `dataSource` | String | `""` | `dataSource` | empty → primary datasource |
| `businessKey` | String[] | `{}` | `businessKey` | composite supported |
| `partitionField` | String | `""` | `partitionField` | |
| (scanner sets) | — | — | `appCode` | stamped server-side from `system.app-code` |
| (DB auto) | — | — | `id` | primary key |

Audit fields (`createdTime` / `createdBy` / `createdId` / `updatedTime` /
`updatedBy` / `updatedId`) come from `AuditableModel` and are **not** declared
via `@Field` — they are auto-injected by `DdlGenerator` when the class
extends `AuditableModel`.

### `@Field` ↔ `SysField`

| `@Field` attribute | Type | Default | `SysField` column | Notes |
|---|---|---|---|---|
| (Java field name) | — | — | `fieldName` | inferred, no override |
| (Java type) | — | — | `fieldType` | inferred via `TypeInference` |
| `label` | String | `""` | `label` | empty → humanized field name (`deptId`→"Dept Id"); i18n translations override by id |
| `description` | String | `""` | `description` | **≤512 chars**, parse-time enforced (catalog column width); concise user-facing summary — design notes go in Javadoc |
| `fieldType` | `FieldType[]` | `{}` | `fieldType` | single value, no braces (e.g. `fieldType = FieldType.MULTI_FILE`); `OPTION`/`MULTI_OPTION` **cannot** be written explicitly |
| `columnName` | String | `""` | `columnName` | empty → `snake_case(fieldName)` |
| `length` | int | `0` | `length` | `0` → type default: STRING/OPTION 64, MULTI_STRING/ORDERS 256, DOUBLE 24 (measurements), BIG_DECIMAL 32 (money); declare explicitly for anything else. On `TEXT` fields length is optional — purely an app-level guard (the column is unbounded). Legacy: MySQL renders STRING `length > 16383` as TEXT (64KB bytes; prefer `fieldType = TEXT`) |
| `scale` | int | `0` | `scale` | `0` → type default: DOUBLE 2, BIG_DECIMAL 8 (DECIMAL scale) |
| `min` / `max` | String | `""` | `constraints` | **value domain**, not a column width — inclusive bounds enforced on every write by `ValueConstraints` (see below); numeric field types only, decimal literals (`"0"`, `"-1.5"`) so a `BigDecimal` bound stays exact. Parsed at scan time: a malformed literal or `min > max` fails the boot |
| `pattern` | String | `""` | `constraints` | regex the **whole** value must match (`Pattern.matches`, not `find`); STRING / TEXT only, compiled at scan time. Keep to syntax Java and JavaScript agree on — the frontend evaluates the same string |
| `constraintMessage` | String | `""` | `constraints` (`message`) | sentence shown as written when `min` / `max` / `pattern` / `invalidWhen` rejects a value; its own i18n key, like `@Index(message)`, not a `{0}` pattern. Optional for a bound ("must be at least 0" composes itself), effectively required for a `pattern` or an `invalidWhen` |
| `requiredWhen` | String | `""` | `constraints` | filter expression over the same row under which the field is required, or `"true"` for application-level required on a nullable column (see below) |
| `readonlyWhen` | String | `""` | `constraints` | filter expression under which assigning the field is rejected |
| `invalidWhen` | String | `""` | `constraints` | filter expression that, when it holds, rejects the write with `constraintMessage` |

A condition is written as an expression — `reason = "Others"` — which in a Java text block needs no
escapes; the full declaration is shown in [entities.md](../../docs/ai/authoring/entities.md) and the
grammar is below under "The expression grammar". The JSON spelling
(`[["reason", "=", "Others"]]`) parses to the same tree and is kept for `IS SET` / `IS NOT SET`, which
the expression grammar cannot parse — write those as a three-element unit,
`[["terminationDate", "IS NOT SET", null]]`. The grammar names fields as `[a-z][a-zA-Z0-9]*`, so a
related row's attribute is reached through a `cascadedField` declared on this model rather than a
dotted path. A value is a number, a boolean, a double-quoted string, a **bare name** — another field
of the same row — or an environment token (`{{ TODAY }}`, see
[placeholders.md](../../docs/ai/authoring/placeholders.md)).

The quotes are what separate the two kinds of name: `endDate < startDate` compares two fields,
`reason = "startDate"` compares a field with the text. Neither typo can pass quietly — a bare name
that is no field of the model fails the boot-time existence check, and a literal written without its
quotes is either an unknown field or, option codes being PascalCase, not a name the lexer accepts at
all. The older spelling `endDate < "{{ @startDate }}"` still parses and produces the identical tree;
it remains what the JSON form and the stored row carry, since only the authoring layer changed.

Compare an option by its item code: a wrong code never fires and never reports.
| `required` | boolean | `false` | `required` | NOT NULL constraint |
| `readonly` | boolean | `false` | `readonly` | UI hint |
| `translatable` | boolean | `false` | `translatable` | i18n-aware column |
| `copyable` | boolean | `true` | `copyable` | `false` ⇒ value not carried over by `copyById` (business keys, credentials, runtime state) |

Copy field-selection contract (applies regardless of the flag): `ONE_TO_ONE` FKs are **always
excluded** — copying one would make two rows share an exclusively-owned related row, corrupting the
1:1 (or hard-failing on its unique index); dynamic fields (`ONE_TO_MANY` / `MANY_TO_MANY` / computed /
cascaded) are excluded because they are not stored columns; `MANY_TO_ONE` **stays copyable** — a shared
reference is exactly its semantics. Historical trap: the `nonCopyable` → `copyable` rename was done as a
migration (V6), NOT via `renamedFrom`, because the rename inverts the value's meaning — a
value-preserving rename would have carried wrong values.
| `unsearchable` | boolean | `false` | `unsearchable` | excluded from default search |
| `computed` | boolean | `false` | `computed` | requires `expression` |
| `expression` | String | `""` | `expression` | AviatorScript |
| `dynamic` | boolean | `false` | `dynamic` | not physically stored |
| `encrypted` | boolean | `false` | `encrypted` | at-rest encryption |
| `autoSequence` | boolean | `false` | `auto_sequence` | auto-fill from a sequence on INSERT when blank; STRING only (not `dynamic`/`computed`/id, RDBMS only); pairs with a `sys_sequence` row `"<Model>.<field>"` (missing row = insert fails, fail-closed). `+ readonly` = strict system numbering (caller values rejected); without = caller values trusted (imports). Never carried on copy |
| `maskingType` | `MaskingType[]` | `{}` | `maskingType` | single element |
| `defaultValue` | String | `""` | `defaultValue` | |
| `relatedModel` | `Class<?>` | `Void.class` | `relatedModel` | Class ref (compile-checked), e.g. `Foo.class`; `Void.class` → inferred from POJO type; **required** for `Long` FK. Use `relatedModelName` (String) for cross-module/dynamic models |
| `relatedModelName` | String | `""` | `relatedModel` | String fallback to `relatedModel` (cross-module/dynamic) |
| `relatedField` | String | `""` | `relatedField` | TO_ONE: always `id` — leave empty (a non-id value is rejected at boot; to store a business code make the related model code-as-id). ONE_TO_MANY: names the child FK column |
| `onDelete` | `OnDelete[]` | `{}` | `on_delete` | TO_ONE FK delete strategy: `RESTRICT` / `CASCADE` / `SET_NULL`; `{}`/unset = KEEP (default — do nothing). App-level (no DB FK). See "Delete strategy" below |
| `joinModel` | `Class<?>` | `Void.class` | `joinModel` | M2M join model class; `joinModelName` (String) fallback |
| `joinLeft` | String | `""` | `joinLeft` | |
| `joinRight` | String | `""` | `joinRight` | |
| `cascadedField` | String | `""` | `cascadedField` | dotted path, e.g. `"owner.name"` |
| `filters` | String | `""` | `filters` | filter expression for relations |
| `widgetType` | `WidgetType[]` | `{}` | `widgetType` | single-element override |
| (scanner sets) | — | — | `modelName` | from enclosing `@Model` class |
| (scanner sets) | — | — | `optionSetCode` | derived from enum type when fieldType is `OPTION`/`MULTI_OPTION` |
| (scanner sets) | — | — | `appCode` / `id` | |
| (FK fixup post-init) | — | — | `modelId` | |
| (not exposed via `@Field`) | — | — | `hidden` | UI-only flag set via Studio |

#### Field constraints (`constraints` — one column, seven attributes)

`length` says how wide the column is; the seven attributes above say **which values** the field
accepts and **when** it applies. They travel together as one `FieldConstraints` record in the single
`sys_field.constraints` column (`FieldType.DTO`, canonical JSON, NULL when nothing is declared) and are
served unchanged on `MetaFieldDTO.constraints`, so the frontend evaluates the same object against the
form. Three kinds of key, told apart by one criterion — does the rule look at other fields, and what
does it conclude:

| | Value domain | Field state | Validity |
|---|---|---|---|
| keys | `min` / `max` / `pattern` (+ `message`) | `requiredWhen` / `readonlyWhen` | `invalidWhen` (+ `message`) |
| looks at other fields | no | yes | yes — it compares them |
| concludes | reject | required / readonly | reject |
| enforced by | `NumericProcessor` (after coercion) / `StringProcessor` (after trim) via `ValueConstraints` | `FieldConstraintsEnforcer`, before the processor chain, on the raw row | same |

```java
@Field(label = "Active Employees", min = "0", constraintMessage = "Headcount cannot be negative.")
private Integer activeEmpCount;

@Field(requiredWhen = """
        reason = "Others"
        """)
private String reasonDescription;

// a bare name on the right is another field of this row; a literal keeps its quotes
@Field(label = "End Date", invalidWhen = """
        endDate < startDate
        """,
       constraintMessage = "End date cannot precede start date.")
private LocalDate endDate;

// more than one condition: AND binds tighter than OR, parentheses override it
@Field(invalidWhen = """
        endDate < startDate OR (grade = 1 AND amount > 1000)
        """,
       constraintMessage = "Check the dates, or the amount against the grade.")
private BigDecimal amount;

@Field(label = "Cost Centre", requiredWhen = "true")     // application-level required, column stays nullable
private Long costCentreId;
```

A condition is an expression in a text block, so nothing is escaped; the grammar — operators, value
forms, `AND` / `OR` precedence, and the shapes it refuses — is below under "The expression grammar".
The JSON spelling parses to the same tree but has
**no precedence**: a group mixing `AND` and `OR` is refused rather than guessed, so it has to be nested
by hand.

Every write reaches the database through the pipeline that checks them — create, update, batch,
import, seed loading, flow write nodes — so one declaration covers all of them. **No `CHECK` is
rendered and no DDL changes**: a rule is tightened by redeploying, rows written before it stay valid.

**Writes only. A query is never judged by a constraint**, and that is a decision rather than an
omission. `DataReadPipeline` runs the output half of the processor chain, which checks nothing, and
`FieldConstraintsEnforcer` is built only by the create and update pipelines. Since a rule may be
declared long after the rows were written, refusing a query that describes what the rule forbids
would make the rows it forbids unfindable — and finding them is the first thing anyone does after
declaring one. `activeEmpCount < 0` is how you list the headcounts a new `min = "0"` has just made
illegal; `["startDate", ">", "{{ @endDate }}"]` (a reserved field reference, compiled to
`t.start_date > t.end_date`) lists every row an `invalidWhen` comparing two dates would now reject.
A client that filters on a value outside the domain gets no rows, which is the correct answer, not
an error. A UI may still refuse to *build* such a filter — narrowing what someone can construct is
not the same as enforcing the rule, and only this pipeline does the latter.

Rules worth knowing before declaring one:

- **Bounds are inclusive, null passes, a blank string is not matched.** Absence is what `required` /
  `requiredWhen` are for; a bounded optional field has to stay leavable empty.
- **The pattern matches the whole value**, `STRING` / `TEXT` only; bounds are decimal literals on
  numeric types only. Keep the regex to the syntax Java and JavaScript agree on.
- **Conditions are filter expressions** (`Filters` syntax, 16 of the 18 operators — `PARENT OF` /
  `CHILD OF` need a query and are refused). Values may be literals, `{{ @field }}` (another field of the
  row), `{{ TODAY }}` / `{{ YESTERDAY }}` / `{{ NOW }}` / `{{ USER_ID }}`, and any of those with an
  ISO-8601 offset: `{{ TODAY - P13Y }}`, `{{ @hireDate + P6M }}`, `{{ NOW - PT2H }}`. In the field slot,
  `@mode` (`create` / `update`) and `@userId` read the context.
- **Semantics both ends share** (`FilterEvaluator`): null and `""` are the same value; equality is
  value equality (`["country", "!=", "SG"]` is *true* for an empty country, unlike SQL); ordering
  operators need two values, their negations answer the opposite; values coerce by the field's type
  (a stored date arrives as text, a patch as `LocalDate`); options compare by item code, relations by
  id, a multi-value field is a set.
- **Visibility is not a constraint.** Whether a field is shown is a property of a view, not of the row
  — the same field is hidden in a list and shown in a form — so a page says it, with a condition of its
  own, and nothing about it reaches the metadata. "This rule applies only while the field is shown" is
  said **on the rule**, as a condition about the row: `requiredWhen = 'type = "CompanyProvided"'`. A
  field that arrives with a value is judged whether or not any screen would have shown it.
- **Static flags win, conditions add.** `effectiveRequired = required || requiredWhen`; declaring both
  is logged at boot. `requiredWhen` renders no `NOT NULL` — that is its point. "Required by default,
  optional in one case" is written as `required = false` + the negated condition — but see the next
  bullet before writing one.
- **A negated operator answers *true* for an empty field.** `["reason", "!=", "Standard"]` holds when
  `reason` is empty, and so do `NOT IN` and `NOT BETWEEN`; that is value equality, and the frontend
  answers the same. It is rarely what you want on `requiredWhen` / `invalidWhen`, where it fires on a
  row the user has not filled in yet: `salary NOT BETWEEN [1000, 9000]` rejects a record with no salary at all. Pair the
  negation with the field being set — `[["reason", "IS SET", null], ["reason", "!=", "Standard"]]` —
  or list the cases positively with `IN`.
- **`requiredWhen = "true"`** is application-level required on a column that must stay nullable
  (`ProjectTeam.costCentreId`): demanded on create and when the field is sent — clearing is rejected,
  omitting is not. Only `requiredWhen` has the always-form.
- **On update, a condition is evaluated only when the patch touches the field or a field it reads**,
  on the patch merged onto the stored row. `DataUpdatePipeline` registers the referenced columns into
  `differFields` in both directions so they are fetched — without that, `PATCH {reason: Others}` would
  slip past a rule that lives on `reasonDescription`. `readonlyWhen` rejects an assignment (a changed
  value), not the value itself.
- **Everything is validated at scan time** against the field's type and the sibling fields a condition
  names: malformed literal, `min > max`, uncompilable regex, wrong field type, unknown sibling,
  incomparable types (`DATE` vs `STRING`), a time offset on a calendar day, a condition on a `dynamic`
  field ⇒ boot failure. A studio / hand-written row failing the same check is logged and dropped at
  catalog load — one bad row must not stop a model from being written.

What the object cannot express — a query, another row, external configuration (`CountryAddressFormat`),
a collection, a permission — goes to the **`ModelWriteValidator` SPI** (`io.softa.framework.orm.service.validation`):

```java
@Component
@Order(110)
public class CountryAddressRuleValidator implements ModelWriteValidator {
    @Override public boolean supports(String modelName) { return "Company".equals(modelName); }
    @Override public void validateCreate(WriteContext ctx) { check(ctx.row(), ctx); }
    @Override public void validateUpdate(WriteContext ctx) { check(ctx.mergedRow(), ctx); }  // patch merged onto the stored row
    private void check(Map<String, Object> row, WriteContext ctx) {
        … ctx.reject("postalCode", "Postal Code is required");   // accumulates; thrown once as WriteValidationException
    }
}
```

Business code never calls a validator: `ModelWriteValidatorChain` runs every bean whose `supports`
answers true, in `@Order` (bands: 0–99 preconditions with `ctx.fail`, 100–199 field / configuration
rules, 200–299 collection / cross-row, 300+ batch-wide and expensive), at the three write roots of
`ModelServiceImpl` — `createList` / `updateList` / `deleteByIds` — so the generic endpoint, a custom
endpoint, the import, a flow write node and a direct `service.createOne` are all covered. Per validator
the batch method runs first, then the rows; values are the caller's, before the pipeline coerces them.

##### Writing a validator

The example above only implements the row methods, which is the right shape for a rule that reads
one row. Two things need the batch method instead.

**A rule between rows of one request.** A duplicate code inside one import file is legal on every
row taken alone; only the whole batch shows the collision. The row method cannot see its siblings.

**A lookup the rows share.** Done in the row method it is one query per row. Done once in the batch
method it is one query per distinct value for the whole write:

```java
@Component
@Order(310)                                        // 300+ — batch-wide and expensive
public class ContractTermValidator implements ModelWriteValidator {

    @Override public void validateBatch(String modelName, List<Map<String, Object>> rows, AccessType at,
                                        Map<String, Object> scratch) {
        scratch.put("policies", new HashMap<String, Policy>());
    }

    @Override public void validateCreate(WriteContext ctx) {
        @SuppressWarnings("unchecked")
        Map<String, Policy> policies = (Map<String, Policy>) ctx.scratch().get("policies");
        Policy policy = policies.computeIfAbsent(countryOf(ctx), this::load);   // one query per country
        …
    }
}
```

`scratch` is the same map in both methods and in every row of that write, and the chain owns it: one
per validator per write, dropped when the write's validation ends. A validator therefore names its
own keys without namespacing them — no other validator is handed this map — and has nothing to clean
up. A context built by `WriteContext.of` (a unit test calling the validator directly) carries an
empty map of its own, so nothing special is needed to test a validator that uses it. A test of the
batch path itself builds one map, hands it to `validateBatch` and then to every row through the
`of(..., patch, originalRow, scratch)` overload — exactly what the chain does — so it can assert that
the second row finds what the first one loaded.

Fill it lazily rather than scanning the rows up front: on update the value a row is judged by often
comes from the stored row rather than from the patch, and the batch method is handed the patches.

Do not park this on a `ThreadLocal` instead. That the batch method and its rows run on one thread is
how the chain works today, not something this interface promises — a validator that keeps its answers
on the thread reads another write's map the day the row loop is parallelised, and there is no hook at
which it could clear them (the chain opens a write with `validateBatch` and closes it with nothing;
`WriteContext` carries the row's index, not the batch's size, so no row recognises itself as last).

**What a batch costs.** A model with no validator pays nothing — the chain returns before touching
the rows. A model with one pays, per batch and not per row: `supports` once, the batch method once,
and on update one read of the stored rows *whole* (the pipeline's own read fetches only the columns
the write changes; a validator is handed the patch merged onto the full row, so that read is made
for it). A query in a row method is the only per-row cost, and it is the one to move.

⚠️ **The import's `skipException` mode defeats all of this.** It first writes the sheet as one
batch; if any row is rejected it rolls back and re-writes the rows *one call per row*. Each of those
calls is a batch of one: the batch method runs again, `scratch` is a fresh map, and a lookup that was
one query for the sheet becomes one query per row. Rows that fail validation are exactly
what triggers it, so a template that declares constraints makes the slow path more likely, not less.
Keep the per-row work in a validator cheap enough that this fallback is survivable.

**The batch method can only throw.** It is handed the rows and its scratch map — no `WriteContext`,
no error collector — so a batch-level rejection aborts the write immediately. Row-level rejections
accumulate and are reported together, which means the caller sees every bad row of a sheet but only
the first cross-row collision. Put a rule in the row method whenever it can be attributed to a row.

##### The expression grammar

`Filters.of` picks the form by the first character: a leading `[` is the list form, anything else is
parsed as an expression. Both produce the same tree; the expression form is what a `@Field` condition
should use, because in a text block it needs no escapes.

```java
Filters.of("status = \"ACTIVE\"")
Filters.of("status = \"ACTIVE\" AND grade >= 6")
Filters.of("title = \"PM\" OR (code = \"A010\" AND grade = 1)")
```


| Part | Accepts |
|---|---|
| field | `[a-z][a-zA-Z0-9]*` — **no dots, no underscores**; reach a related row's attribute through a `cascadedField` declared on this model |
| operator | `=` `!=` `>` `>=` `<` `<=` `CONTAINS` `NOT CONTAINS` `START WITH` `NOT START WITH` `IN` `NOT IN` `BETWEEN` `NOT BETWEEN` `IS SET` `IS NOT SET` `PARENT OF` `CHILD OF` |
| value | a number, `true` / `false`, or a **double-quoted** string (single quotes are not a string); a list as `["a", "b"]` |
| combining | `AND` / `OR`, grouped with parentheses to any depth; **`AND` binds tighter**, so `a AND b OR c` is `(a AND b) OR c` |
| whitespace | ignored, so a text block's trailing newline is harmless |

Combining more than two conditions is the case where the two forms genuinely differ. The expression
form has precedence, so a mixed rule needs no nesting — and parentheses override it where the default
reading is not what you meant:

```java
@Field(requiredWhen = """
        reason = "Others" AND status = "Draft"
        """)

@Field(invalidWhen = """
        endDate < "{{ @startDate }}" OR (grade = 1 AND amount > 1000)
        """)
```

The **list form has no precedence at all**, so a group that mixes `AND` and `OR` is refused rather
than guessed — `The logic operator is not unique` — and you have to nest the groups by hand:

```
[[["a", "=", 1], "AND", ["b", "=", 2]], "OR", ["c", "=", 3]]
```

Two limits worth knowing before you choose the form:

- **`IS SET` / `IS NOT SET` have no expression form.** They take no value, and the visitor demands
  one, so they throw. Write those in the list form, and give the unit a third element that the
  operator then ignores: `[["terminationDate", "IS NOT SET", null]]`.
- **`PARENT OF` / `CHILD OF` need a query**, so they are refused in a field constraint (a constraint
  is evaluated against one row in hand). They are available in a query's own filters.

Both forms parse to the same `Filters` tree; a test pins that equivalence.

##### Keeping a second evaluator in step

These rules are evaluated **twice** — here on write, and by whatever renders the form, against the
values the user is typing. The feature only works while the two agree, and they did not: auditing the
first such implementation found **seven** declarations where the same row produced opposite answers,
and three review rounds found fifteen defects in that evaluator, each one a sentence of this contract
read differently. Prose does not hold that line; a reader implements what they understood and nothing
fails until a user meets it.

What holds it is a **corpus**: a data file of `(declaration, row, expected)` rows, generated by running
every case through `FilterEvaluator` itself, that a second implementation runs as a test.

```json
[
  { "case": "null and empty text are one value",
    "condition": "reason = \"\"",
    "fieldTypes": { "reason": "Option" },
    "row": { "reason": null },
    "expected": true }
]
```

`expected` is never hand-written, so it cannot drift from this code. The cases worth carrying are the
ones that have actually caught something:

| Group | The case that catches a naive implementation |
|---|---|
| emptiness | `country != ""` with `country: null` → **false** (two empties are equal, so their inequality is not) |
| emptiness | `tags IS SET` with `tags: []` → **false**; `IS NOT SET` → true |
| ordering | `endDate < "{{ @startDate }}"` on an empty row → **false** (or a blank create form refuses itself) |
| ordering | `NOT BETWEEN` with an empty left side → **true** |
| coercion | `code = "7"` with `code: "007"` on a **String** field → **false** |
| coercion | `amount = 10` with `amount: "10.00"` on a numeric field → true |
| coercion | a **Date** truncates the time away: a field holding today is not "before now" at 10am |
| coercion | a **Time** keeps its fraction: `09:00:00.500` is not `09:00:00` |
| coercion | a relation compares as **text**: `managerId > 100` with `42` → true |
| sets | `tags = "a"` with `["a","b"]` → true (membership, not equality) |
| context | `@mode` / `@userId` read the write, not the row |

`TODAY` / `NOW` resolve on the server's clock here and on the browser's clock in a form. The corpus
supplies the instant per case rather than reading a real clock, so it tests the arithmetic; the
timezone difference is a known open question it cannot settle.

Two rules live in `FieldConstraintsEnforcer` rather than in the expression, and a client rendering a
form mirrors those too: a conditional rule is evaluated on update only when the patch touches the field
or something it reads; and `requiredWhen = "true"` fires on create but on update **only when the write
names the field** — rendered as a plain required field it makes every row written before the rule
unsaveable.

Add a case whenever a defect is found in any implementation, named after the rule it protects rather
than the bug. It is worth more here than in one codebase's test file, because it then constrains every
implementation.

#### Delete strategy (`onDelete`)

On a `MANY_TO_ONE` / `ONE_TO_ONE` FK, `onDelete` declares what happens to the **referencing** rows when
the referenced ("One") row is deleted. Enforced application-level in `ModelServiceImpl.deleteByIds` — no
physical DB `FOREIGN KEY ... ON DELETE` is ever emitted. Why app-level and never a real DB FK: soft
delete is an `UPDATE`, invisible to a DB `ON DELETE` (the FK would simply never fire); a DB cascade
bypasses permissions, change logs, audit stamping, soft-delete conversion and tenant scoping; a DB FK
cannot express "count only `deleted=false` referrers", "block regardless of tenant", or "null only on
hard delete"; and physical FKs clash with the never-auto-DROP DDL governance. Strategies:

- `RESTRICT` — block the delete if any live (`deleted=false`) referrer exists.
- `CASCADE` — delete the referrers in the same transaction (each follows its own soft/hard delete).
  **Rejected at boot** if a soft-delete One would cascade to a hard-delete Many (a recoverable parent
  must not irreversibly delete children — make the Many soft-delete too, or use RESTRICT/SET_NULL).
- `SET_NULL` — null the referrer FK; **only on a hard delete** of the One (no-op on soft delete, so a
  restore still resolves the link). Requires a nullable FK (`required = false`).
- unset (`{}` / `on_delete` NULL) = **KEEP** (default) — the framework does nothing.

**CASCADE soft/hard-delete matrix** — the cascade on each Many follows the *Many's* own delete mode
(not the One's); the one unsafe combination is rejected at boot:

| One (referenced / parent) | Many (referrer / child) | CASCADE result |
|---|---|---|
| soft-delete | soft-delete | Many **soft-deleted** (both recoverable) |
| soft-delete | hard-delete | **rejected at boot** — a recoverable parent must not irreversibly delete children |
| hard-delete | soft-delete | Many **soft-deleted** |
| hard-delete | hard-delete | Many **hard-deleted** |

A `CASCADE` from a **shared (non-multi-tenant) parent to a multi-tenant child** is likewise rejected at
boot — one delete would cascade across all tenants (use RESTRICT).

**Runtime safety** — a `CASCADE` / `SET_NULL` affecting more than `MAX_BATCH_SIZE` referrers *per cascade
level* is rejected: `referrerIds` fetches at most `MAX_BATCH_SIZE + 1` ids in one `LIMIT`-ed query, so an
over-limit delete fails fast **without loading the full set** (bounded memory, no extra `count`). Large
deletes are chunked to `DEFAULT_BATCH_SIZE` to bound the SELECT/DELETE statement + IN-clause size (same
transaction — chunking bounds statement size, not lock duration).

For a OneToMany "delete parent → delete children", put `CASCADE` on the **child's back-reference FK**
(the FK is the single source of truth; `onDelete` is not declared on `ONE_TO_MANY`).

Boot-time guards (fail-fast): `onDelete` is valid only on TO_ONE; `SET_NULL` requires a nullable FK; a
**cyclic / self-referential `CASCADE`** is rejected (delete such hierarchies — org trees, BOM, category
trees — in application code); a **`CASCADE` chain deeper than `MAX_CASCADE_DEPTH` models** is rejected
(bounds recursion; the error names the full chain); and a `CASCADE` from a **soft-delete parent to a
hard-delete child**, or from a **shared parent to a multi-tenant child**, is rejected (see the matrix
above).

A **timeline** target is allowed: the inbound-FK strategy fires on **entity deletion** (`deleteByIds`,
which removes all slices of the logical id — referencing FKs store that logical id, so RESTRICT counts /
CASCADE deletes / SET_NULL nulls by it, no effective-date resolution involved); slice-level
`deleteBySliceId` keeps the entity alive and deliberately does not trigger it.

### `@OptionSet` ↔ `SysOptionSet`

| `@OptionSet` attribute | Type | Default | `SysOptionSet` column | Notes |
|---|---|---|---|---|
| (enum simple name) | — | — | `optionSetCode` | inferred, no override |
| `name` | String | `""` | `name` | display label; empty → humanized enum name (`TenantStatus`→"Tenant Status") |
| `description` | String | `""` | `description` | **≤512 chars**, parse-time enforced (catalog column width); concise user-facing summary — design notes go in Javadoc |
| (scanner sets) | — | — | `appCode` / `id` | |
| (Studio toggle) | — | — | `deleted` / `optionItems` | runtime aggregation |

### `@OptionItem` ↔ `SysOptionItem`

| `@OptionItem` attribute | Type | Default | `SysOptionItem` column | Notes |
|---|---|---|---|---|
| (`@JsonValue` field value on enum) | — | — | `itemCode` | fallback to `enum.name()` when no `@JsonValue` |
| (enclosing enum simple name) | — | — | `optionSetCode` | inferred |
| `label` | String | `""` | `label` | defaults to humanized constant name (`MULTI_FILE`→"Multi File"); declare explicitly to customize. Omit when it equals the humanized name (and omit the whole `@OptionItem` if nothing else remains) |
| `description` | String | `""` | `description` | **≤512 chars**, parse-time enforced (catalog column width); concise user-facing summary — design notes go in Javadoc |
| `sequence` | int | `-1` | `sequence` | `-1` → use `ordinal() + 1` |
| `parentItemCode` | String | `""` | `parentItemCode` | hierarchy |
| `itemTone` | `OptionItemTone[]` | `{}` | `itemTone` | single element |
| `itemIcon` | `OptionItemIcon[]` | `{}` | `itemIcon` | single element |
| (scanner sets) | — | — | `appCode` / `id` / `optionSetId` | |
| (Studio toggle) | — | — | `active` | |

### `@Index` ↔ `SysModelIndex`

`@Index` is `@Repeatable` — stack multiple declarations on one `@Model` class.

| `@Index` attribute | Type | Default | `SysModelIndex` column | Notes |
|---|---|---|---|---|
| (enclosing class) | — | — | `modelName` | inferred |
| `indexName` (or auto-derived) | String | `""` | `indexName` | `idx_<table>_<col>...` / `uk_<table>_<col>...` for unique; **globally unique** (boot-enforced), ≤60 chars |
| `fields` | String[] | required | `indexFields` | **camelCase Java field names**, not column names |
| `unique` | boolean | `false` | `uniqueIndex` | |
| `message` | String | `""` | `message` | end-user text on a unique-constraint violation (**only when `unique`**); its own i18n key; empty → composed from the member fields' labels |
| (scanner sets) | — | — | `appCode` / `id` | |
| (FK fixup post-init) | — | — | `modelId` | |

**Note**: `@Model.businessKey` does **not** auto-create a UNIQUE index.
Multi-tenant models typically want `UNIQUE (tenant_id, businessKey...)`
which has tenant-aware semantics not expressible by `@Index` alone —
declare such indexes explicitly:
```java
@Index(fields = {"tenantId", "code"}, unique = true)
```

## Runtime catalog identity (`app_code`)

Rows in `sys_model` / `sys_field` / `sys_option_set` / `sys_option_item` /
`sys_model_index` are scoped by **`app_code`**, stamped server-side from
`system.app-code`. The retired `ownership` tier column is gone from
the baseline — the annotation lane and the Studio no-code lane reconcile the
**same rows matched by business key** (`modelName` / `fieldName` /
`optionSetCode` / `itemCode`, plus `renamedFrom`).

In development, packages listed in `scanner-scope` reconcile annotation-derived
metadata into `sys_*` for this app. In production, Studio/connector publish
applies the app-scoped design catalog. Per-tenant runtime metadata
customization is not represented as separate `sys_*` rows.

Verify after boot:

```sql
SELECT model_name, app_code FROM sys_model WHERE app_code = '<system.app-code>';
```

The `Ownership` enum (`io.softa.framework.orm.enums.Ownership`) remains in code
as a reserved business-data concept; nothing reads or writes an `ownership`
column on current runtime `sys_*` tables.

See [`starters/metadata-starter/README.md`](../../starters/metadata-starter/README.md)
for scanner-scope, DDL policy, and `renamedFrom` handling.

## Runtime control annotations
### `@DataSource`
Switch the current method or class to a named datasource.

Behavior:
- Method-level annotation overrides class-level annotation.
- If no datasource is currently bound, the target datasource is used for the method scope.
- If the same datasource is already bound, no switch happens.
- If a different datasource is already bound and a Spring transaction is active, the framework throws an exception instead of switching.

Typical usage:
```java
@DataSource("db1")
public void readFromDb1() {
    // ...
}
```

### `@Debug`
Temporarily sets `Context.debug=true` for the annotated method.

Behavior:
- Enables SQL debug output for methods intercepted by `@ExecuteSql`.
- `ExecuteSqlAspect` logs SQL text, parameters, partial results, and elapsed time while debug mode is enabled.

Typical usage:
```java
@Debug
public List<Map<String, Object>> inspectQuery() {
    return jdbcService.searchList(...);
}
```

### `@SwitchUser`
Clones the current context, replaces the current user name with a system user or alias, and sets
`skipPermissionCheck=true` for the method scope.

Typical usage:
```java
@SwitchUser(SystemUser.CRON_USER)
public void runAsCronUser() {
    // ...
}
```

Notes:
- This is useful for scheduled tasks, system jobs, or framework-level operations.
- It changes the execution context user name, not the datasource or tenant mode.

### `@SkipPermissionCheck`
Temporarily sets `Context.skipPermissionCheck=true` for the annotated method.

Typical usage:
- metadata loading
- internal framework pipelines
- trusted system entry points

### `@RequireRole`
Intended to require a specific `SystemRole` before entering the method.

Current implementation note:
- The role check is still marked `TODO` in `PermissionAspect`.
- Today it mainly behaves like a placeholder wrapper that enables `skipPermissionCheck` after the future role check hook.

### `@SkipAutoAudit`
Temporarily disables automatic audit field filling for the current method scope.

Effect:
- created/updated audit fields are not auto-filled while the method runs.

### `@DataMask`
Toggles response/result masking in the current method scope.

Behavior:
- `@DataMask` or `@DataMask(true)` enables masking.
- `@DataMask(false)` disables masking temporarily, which is useful for trusted internal endpoints.

### `@ExecuteSql` and `@WriteOperation`
These are infrastructure annotations used by the low-level JDBC proxy layer.

Behavior:
- `@ExecuteSql` marks a method as an SQL execution entry point.
- In read-write-separation mode, datasource routing happens around `@ExecuteSql`.
- `@WriteOperation` tells the read/write router that the method must use the primary datasource when not inside a transaction.

Recommendation:
- Most application service methods should not add these annotations directly.
- Use them when building framework-level JDBC wrappers or custom low-level SQL executors.

### Less Common Annotations
- `@RPCCheckpoint`: framework-internal AOP hook used by `JdbcServiceImpl` to
  redirect ORM calls to the app that owns the model — `SwitchServiceAspect`
  routes by the model's `appCode` when it differs from this runtime's
  `system.app-code`. Application services do not apply this annotation themselves.
  See [Service-to-Service RPC](../architecture/rpc.md) for
  the mechanism, wire format, and configuration.
- `@CrossTenant` and `@PerTenant`: covered in the multi-tenancy section below.

## Multi-Tenancy
### Runtime Preconditions
To use shared-db multi-tenancy correctly:
- set `system.enable-multi-tenancy=true`
- mark the model metadata with `multiTenant=true`
- ensure the model contains a `tenantId` field

Startup validation:
- `ModelManager` validates that every `multiTenant=true` model contains `tenantId`
- otherwise startup fails with: `The multi-tenant model {modelName} must contain the tenantId field`

### Default ORM Behavior
When multi-tenancy is enabled and the current context is not cross-tenant:
- reads automatically append `tenant_id = Context.tenantId` for multi-tenant models
- inserts automatically fill `tenantId` from the current context
- non-multi-tenant models are not affected

When `Context.crossTenant=true`:
- tenant filtering is skipped
- tenant auto-fill on insert is skipped

This means cross-tenant writes must set `tenantId` explicitly if you still want to write tenant-owned rows.

### `@CrossTenant`
Use this when a method must run once and see data across all tenants.

Behavior:
- clones the current context
- sets `crossTenant=true`
- sets `skipPermissionCheck=true`
- runs the method once

Typical usage:
```java
@CrossTenant
public void rebuildGlobalStatistics() {
    // ORM reads are not restricted by tenant_id here
}
```

Use cases:
- global reconciliation
- data migration
- admin-wide reporting

### `@PerTenant`
Use this when one method invocation should be expanded into one execution per active tenant.

Behavior:
- requires `TenantInfoService`, which means multi-tenancy must be enabled
- method return type must be `void`
- queries active tenant IDs from `TenantInfoService`
- runs once per active tenant
- sets `tenantId` for each invocation
- sets `skipPermissionCheck=true` for each invocation
- uses virtual threads with max concurrency `100`
- waits for all tenant executions and throws after collecting failures

Typical usage:
```java
@PerTenant
public void syncTenantCache() {
    // Runs once per active tenant with that tenant's context
}
```

Use cases:
- per-tenant scheduled jobs
- tenant-local cache refresh
- tenant-local reconciliation

Important rule:
- Do not combine `@PerTenant` with upstream fan-out that already split work per tenant
  (for example, `cron-starter` with `SysCron.tenantJobMode=PerTenant`), otherwise the job is expanded twice.

## Request-scoped narrowing

Tenant isolation is not the only read narrowing the ORM applies on its own. Reads of a model declared `@Model(multiCountry = true)` — rows replicated per country: pass types, ID types, employment types — are narrowed to **the countries the caller works in**.

That set is the caller's own: the countries of the companies their roles reach, bridged from the permission snapshot onto the request `Context` as `grantedCountries` (beside `grantedCompanyIds`, the grant itself). Two companies in the same country share their value domains, so the set is by country, not by company. Nothing is read from the request: a header switcher used to select one company and the narrowing followed it; the switcher is gone, and with nothing to select the grant is the answer.

```
permission snapshot  ──bridge──▶  Context.grantedCountries = {SG, NZ}
                                       └─▶ MultiCountryScope → country IN ('NZ','SG')

roles reach no company (self-service)  ▶  grantedCountries = {}  ,  EmpInfo.companyId = 4242
                                       └─ CompanyCountryEnricher → Context.companyCountry = 'SG'
                                            └─▶ MultiCountryScope → country IN ('SG')

no snapshot consulted (public, MQ, job) ▶  grantedCountries = null , no companyCountry
                                       └─▶ MultiCountryScope → skipped
```

`ModelServiceImpl` applies it on every read, then the role data scope on top:

```java
permissionService.appendScopeAccessFilters(model, MultiCountryScope.append(model, callerFilters));
```

**The narrowing feeds the permission call; it does not wrap its result.** It skips when the caller already constrains the country field — what lets a form scope its dropdowns by the legal entity picked *in the form*, one country out of the caller's several. Applied to the permission call's output, that check would also see the conditions a role grant just added. Fed as the input, it sees only the caller's own filters and the grant is AND-ed on afterwards.

**There is no per-company narrowing.** `@Model(multiCompany = true)` still marks a model whose rows belong to one company, and the anchor contract below still holds — but the flag now only names the field the permission grant (`appendCompanyGrant`) bounds every such read by. A `MultiCompanyScope` used to narrow within the grant to the company selected in the header; it went with the header.

### Resolving the anchor

The anchor is **fixed by name**, not resolved per model, and `ModelManager` fails the boot rather than letting a marked model quietly go unnarrowed or unbounded:

| declaration | required anchor field |
|---|---|
| `multiCountry = true` | `country` — a `MANY_TO_ONE` / `ONE_TO_ONE` onto `CountryRegion` |
| `multiCompany = true` | `companyId` — a `MANY_TO_ONE` / `ONE_TO_ONE` onto `Company` |

Fixing the name is what separates the axis from an attribute. A model may reference a country or a company for other reasons — the country that issued a document, the countries a bank serves, the entity that pays a pay group (`PayGroup.payingEntityId`) — and only the field carrying the reserved name says "these rows belong to it". Resolving by relation target alone could not tell the two apart, and guessing would narrow every read on the wrong axis: a plausible-looking wrong answer, which is worse than a visible break. Because the name is fixed, nothing has to be stored, looked up or disambiguated at runtime.

A model with no company column of its own — a per-department statistic — declares the reference as a **`dynamic` cascaded field**, which takes no column and is joined at query time:

```java
@Field(label = "Company", cascadedField = "deptId.companyId", dynamic = true,
        fieldType = FieldType.MANY_TO_ONE, relatedModel = Company.class)
private Long companyId;
```

`WhereBuilder` rewrites a condition on that field back to the cascade path, so a grant on it compiles to a LEFT JOIN and the anchor stays a plain field name that can also be filtered, sorted and displayed. A real `company_id` column is the worse alternative: it needs backfilling every time a department is re-parented, and a stale copy shows up as a report quietly missing rows.

Rejected at boot: the anchor field missing entirely; a field of that name that is not a to-one onto the target model (this one would otherwise *run*, comparing ids across two models and matching nothing — indistinguishable from missing data); and the company model itself being `multiCompany`.

A to-many reference is never an anchor. A bank serving many countries is not partitioned by them — that field is an attribute, and the model is simply not `multiCountry`.

### Grant, countries, affiliation

Three things, kept apart:

- **The grant** — `PermissionInfo.grantedCompanyIds`, bridged as `Context.grantedCompanyIds`: which companies' *records* the caller may see, applied by `appendCompanyGrant` to every model carrying the company anchor. Tri-state: `null` = unrestricted (an unconfigured role), **empty** = reaches no company at all (only ever an explicit configuration — a self-service role), non-empty = exactly those. It is the role's ordinary data scope on the company model (`role_data_scope` where `model = 'Company'`), resolved by `DefaultPermissionSnapshotProvider.readGrantedCompanyIds`; there is deliberately no company scope type and no store of its own.
- **The countries** — `PermissionInfo.grantedCountries`, bridged as `Context.grantedCountries`: the countries of those companies, read in the same snapshot build. Concrete even for an unrestricted grant (every company of the tenant), because "no company restriction" is not "every country": an SG-only tenant's administrator works in SG whatever a value domain was seeded for. `null` only when unknown — a request that never consulted the snapshot — and a reader must treat that as "do not narrow", never as "none".
- **The affiliation** — `EmpInfo.companyId` / `USER_COMP_ID`, "the company I belong to": anchors permission rules and must stay out of the grant (a grant derived from it makes one role behave differently per holder). On the country axis it is the fallback: a self-service employee whose roles reach no company has an empty set, and `CompanyCountryEnricher` puts their own company's country on `Context.companyCountry` so their dropdowns still narrow.

### When narrowing is skipped

Every skip is silent by design: an over-eager condition empties a list the user needs, which is worse than showing too much.

- **nothing known** — the set is `null` (no snapshot consulted: anonymous and public endpoints, service-to-service calls, scheduler and MQ threads, import jobs) or empty with no own company on the context. Callers on those paths that need a country pass it themselves.
- **the caller filters by `id`** — a display expansion, a by-id read, a cascade resolving stored values. `XToOneGroupProcessor` expands a stored `ManyToOne` with `searchList(relatedModel, id IN (…))`; narrowing that would render a row's stored reference blank whenever it belongs to a country outside the set. Note `FilterControl.bypassAll()` does *not* cover this — it only waives active-control and soft-delete.
- **the caller already constrains the country field** — see above; an import resolving a row against the row's own country relies on this too (`RelationLookupResolver`).

### Cost

The set is on the context before the first read — one Redis round trip for the permission snapshot, which the interceptor pays anyway. The affiliation fallback costs `CompanyCountryEnricher` one company → country lookup per request for callers with an employee record, cached in Redis for five minutes — short deliberately, because the country is an editable field on the company's own form and there is no eviction hook on the generic write path. If it cannot be resolved (a deleted company, a row with no country, an application with no company model), the fallback is simply absent and the narrowing skips for that caller.

## Configuration
### MQ Topic
```yml
mq:
  topics:
    change-log:
      topic: 
```

### Multi-Datasource
Application Scenarios:
* Read-write separation
* Operate multiple databases in one project

#### Multiple Datasource Configuration
The multi-datasource is enabled by configuring `spring.datasource.dynamic.enable = true`.
Otherwise, using the original `spring.datasource.*` as the single datasource.

The first datasource is the default datasource when not specified in annotation. 
The datasource name can be customized in the `application.yml` file.
```yml
spring:
  datasource:
    dynamic:
      enable: true
      # mode: read-write-separation, switch-by-model, multi-tenancy-isolated, multi-datasource(default)
      mode: read-write-separation
      datasource:
        primary:
          driver-class-name: com.mysql.cj.jdbc.Driver
          url: jdbc:mysql://localhost:3306/demo
          username: user0
          password: pass0
        db1:
          driver-class-name: com.mysql.cj.jdbc.Driver
          url: jdbc:mysql://localhost:3306/db1
          username: user1
          password: pass1
        db2:
          driver-class-name: com.mysql.cj.jdbc.Driver
          url: jdbc:mysql://localhost:3306/db2
          username: user2
          password: pass2
```

#### Specify the datasource in Java code
The name of the datasource is the same as the key in the `application.yml` file.
```java
@DataSource("db1")
public void method1() {
    // ...
}
```
Datasource propagation mechanism:
* If the method does not have the DataSource annotation, get the class level annotation.
* If the previous datasource is the same as the current datasource, no need to switch.
* If the previous datasource is different from the current datasource, throw an exception.
* If the previous datasource is null, set the specified datasource as the current datasource.
* If the datasource is set firstly, it will be cleared after the method is executed.

#### Deal with the problem of read-after-write consistency
In the read-write separation scenario, and non-transactional context environment, the read-after-write consistency problem may occur.
The solution is to use the `@DataSource` annotation to specify the datasource for the read operation.
```java
// When 'primary' is the writable datasource.
@DataSource("primary")
public void readMethod1() {
    // ...
}
```

## Timeline Model
A timeline model records historical slices of data over time. It is useful for business data that depends on an effective date (for example, department structures or reports that change before/after a specific date). A business record `id` can have multiple slices; each slice is identified by `sliceId`, and `effectiveStartDate`/`effectiveEndDate` define the effective range.

### 1. Timeline Model Metadata

#### 1.1 Timeline Attribute at Model Level
- `timeline = true` indicates this is a timeline model. It must contain the reserved fields `effectiveStartDate` and `effectiveEndDate`. The system validates these fields on startup and throws an exception if missing.
- `timeline = false` indicates a non-timeline model. Non-timeline models must not define the reserved fields `effectiveStartDate` and `effectiveEndDate`.
- A timeline model **requires an app-generated logical id** — `idStrategy = DISTRIBUTED_LONG` (or
  `DISTRIBUTED_STRING` / `EXTERNAL_ID`). `DB_AUTO_ID` is rejected at boot: the auto-increment lands on the
  physical `sliceId`, so nothing would fill the shared logical `id` column of a first slice (split/correct
  rows arrive carrying the entity's existing id and keep it).
- A timeline model must **not** declare `activeControl` (rejected at boot): `active` is an entity-level
  switch, while timeline storage makes every field per-slice — the combination would silently mutate the
  feature's semantics and blind the interval algorithm's neighbor probes. Express period state as a
  versioned business field; terminate the timeline via `setEndDate` (§2.6).

#### 1.2 Primary Keys and Fields
- `sliceId`: physical primary key of a timeline model, used to update a slice.
- `effectiveStartDate`: effective start date of the timeline data.
- `effectiveEndDate`: effective end date of the timeline data.
- `id`: logical (business) primary key, compatible with non-timeline models. All business foreign keys referencing a timeline model use this field.
- If your database needs an auto-increment record number (such as `record_id`) for change logs, you can add it yourself. It is not a framework-reserved field.
- Recommended unique constraint: `(id, effectiveStartDate, effectiveEndDate)` — one index doubles as the
  as-of read cover (the end date is checked in-index) and as an integrity backstop: interval maintenance is
  a check-then-act sequence, so a true concurrent write race on one entity surfaces as a unique violation
  instead of silent same-start slices. Declare it with an **explicit `indexName`** (the default concatenated
  name exceeds the 60-char global limit for longer table names):

  ```java
  @Index(indexName = "uk_<table>_timeline",
         fields = {"id", "effectiveStartDate", "effectiveEndDate"}, unique = true)
  ```

#### 1.3 Metadata Relationships
- Timeline models can relate to themselves via One2One, Many2One, One2Many, Many2Many. Storage and references use the logical primary key `id`.
- When a timeline model relates to a non-timeline model, relation tables store the timeline model logical key `id`.
- When a non-timeline model relates to a timeline model, Many2One/One2One fields and Many2Many join tables store the timeline model logical key `id`.
- Association reads use `effectiveDate` by default (current date if not specified), so there may be no effective slice for the current date.
- In cascade query chains (for example, timeline -> non-timeline -> timeline), `effectiveDate` should be propagated to the last model to keep consistency.

#### 1.4 Cascaded Fields
- Cascaded fields are based on Many2One/One2One associations. When the related model is a timeline model, `Context.effectiveDate` is used to query the related data.

#### 1.5 Timeline Data Concepts
- Every slice must have `effectiveStartDate` and `effectiveEndDate`, and slices for the same `id` are expected to be continuous and non-overlapping.
- To simplify queries, the last slice typically uses `effectiveEndDate = 9999-12-31`.
- In most cases, you only need to set `effectiveStartDate`; the system computes and fills `effectiveEndDate` based on adjacent slices.
- Physical record: each slice is a physical record (identified by `sliceId`). Any change in effective dates creates or updates physical slices. Change logs are bound to physical records.
- Logical record: a group of physical slices that share the same logical `id`. Business foreign keys reference the logical `id`, and association reads return the slice effective on the requested date.

Example timeline slices (same logical department `id`):  

| sliceId (physical) | id (logical) | Department Code | Department Name | effectiveStartDate | effectiveEndDate | Manager |
| --- | --- | --- | --- | --- | --- |  |
| 3 | 6 | D001 | Product R&D Dept | 2022-09-01 | 9999-12-31 | Joan |
| 2 | 6 | D001 | R&D Dept | 2020-05-11 | 2022-08-31 | Tom |
| 1 | 6 | D001 | R&D Dept | 2019-08-01 | 2020-05-10 | Mars |

### 2. Common Scenarios

#### 2.1 Effective Date Propagation
- `effectiveDate` is a `LocalDate` stored in `Context`, defaulting to the current date.
- Query data effective on a specific date:
  `effectiveStartDate <= effectiveDate && effectiveEndDate >= effectiveDate`
- Query data effective within a period (startDateValue, endDateValue must be non-null):
  `effectiveStartDate <= endDateValue && effectiveEndDate >= startDateValue`
- To query all slices for a business record, use `acrossTimelineData()` with `id` filters (or include `effectiveStartDate/effectiveEndDate` in filters).
- Typical adjacent slice lookups:
  `previous: id = {id} AND effective_end_date = {effectiveStartDate - 1}`
  `next: id = {id} AND effective_start_date = {effectiveEndDate + 1}`

#### 2.2 read/search APIs
- Queries like `getById/getByIds/searchList/searchPage` return only slices effective on `effectiveDate` by default.
- To query history across time, use `FlexQuery#acrossTimelineData()` or include `effectiveStartDate`/`effectiveEndDate` in filters.
- Cascaded reads propagate `effectiveDate`.
- **View a record's version list from the REST API**: `/searchPage` (or `/searchList`) with the row's
  `id` in `filters` and `acrossTimeline: true` returns all slices (each carrying its own `sliceId` and
  effective range). Narrow field selections on a timeline model automatically round-trip `sliceId`
  (like `version` under optimistic locking): version rows stay actionable — correct via `update` /
  remove via `deleteBySliceId` — without re-querying. The `acrossTimeline` flag is the explicit half of the dual trigger, exposed on
  `QueryParams` / `SearchListParams`; it is not on `SearchNameParams` (a displayName picker wants the
  as-of option, not every version). Example:
  ```jsonc
  POST /{model}/searchPage
  { "filters": [["id","=",6]], "orders": [["effectiveStartDate","DESC"]], "acrossTimeline": true }
  ```

#### 2.3 create APIs
- For `createOne/createList`, if `effectiveStartDate` is empty, it uses the current `effectiveDate`; if `effectiveEndDate` is empty, it is set to `9999-12-31`.
- If an existing `id` is provided, the system automatically splits or adjusts adjacent slices based on the new `effectiveStartDate`.
- **The write intents, made explicit:**

  | Intent | API | Key |
  |---|---|---|
  | Create a NEW entity | `create*` **without** `id` | fresh logical `id` + genesis slice |
  | Add a version to an EXISTING entity | `addVersion` (or `create*` with the existing `id`) | returns the new `sliceId` |
  | Correct one existing version | `update*` | keyed by `sliceId` (any supplied `id` is overwritten from the DB) |
  | Terminate / reopen the timeline | `setEndDate` (§2.6) | keyed by logical `id`; writes the LAST slice's end date |

- `addVersion(modelName, row)` is the explicit add-version entry (REST: `POST /{model}/addVersion`,
  counterpart of `deleteBySliceId`): the row must carry the existing entity's `id`, and it returns the new
  version's `sliceId` (when the start date matches an existing slice, that slice is corrected in place and
  its `sliceId` is returned). `addVersionAndFetch` also returns the full version row, fetched by `sliceId`
  across the timeline (the new version's effective date may not be today).
- **Guard**: a `create*` call carrying an `id` that matches **no** entity is rejected for
  `DISTRIBUTED_LONG/STRING` models — a typo must not silently mint a new entity with a caller-chosen id.
  Exceptions: `EXTERNAL_ID` models (new entities legitimately arrive with their id) and the
  `enableInsertId` import mode (preset ids).

#### 2.4 update APIs
- The current implementation uses `sliceId` as the update primary key. Updating `effectiveStartDate` automatically corrects adjacent slices' `effectiveEndDate`.
- `effectiveEndDate` is system-computed and **stripped from every generic update write**; the single
  sanctioned write path is `setEndDate` (§2.6). To create a new slice, use `create` with an existing `id`
  and a new `effectiveStartDate`.
- If an upper layer provides a "correct"-style API (update data without creating a new slice), it should locate by `sliceId` (the ORM currently does not provide a dedicated correct API).

#### 2.5 delete / copy APIs
- `deleteById/deleteByIds`: deletes all slices for a business `id` — this is **entity deletion**, and it is
  the point where the inbound-FK delete strategy (`onDelete` RESTRICT / CASCADE / SET_NULL, keyed by the
  logical `id`) fires against referencing models.
- `deleteBySliceId`: deletes a single slice and automatically corrects adjacent slice ranges. The entity
  survives, so `onDelete` deliberately does **not** fire.
- `copyById/copyByIds`: copies the **current (as-of) slice** into a **new entity** — the copyable field set
  excludes every structural timeline key (`id`/`sliceId`/effective dates), so the copy gets a fresh logical
  `id` and a genesis slice at the current date. It does **not** duplicate the full version history, and does
  not add a slice to the source entity. (`businessKey` fields are `copyable = false`, so set a new code on
  the copy.)

#### 2.6 Termination & gaps (`setEndDate`)
- `setEndDate(modelName, id, endDate)` (REST: `POST /{model}/setEndDate`) writes the `effectiveEndDate`
  of the entity's **LAST** slice — the single sanctioned write to the system-computed end date. An
  `endDate` before `9999-12-31` **terminates** the timeline: as-of reads after it return nothing.
  Passing `9999-12-31` **reopens** it. The tail slice is resolved server-side from the logical `id`, so
  callers never race a stale `sliceId`.
- `endDate` must not precede the tail slice's own `effectiveStartDate` — delete the trailing version(s)
  first (`deleteBySliceId`) to terminate earlier; nothing is ever implicitly discarded.
- **Revive**: a later `addVersion` whose start is after a terminated end date inserts a fresh open
  segment, deliberately leaving a **gap**. Routing falls out of the existing algorithm — no special
  cases:

  | `addVersion` start lands... | Behavior |
  |---|---|
  | inside existing coverage | normal split / same-start correct; a terminated end date survives the split |
  | inside a gap, before a later segment | fills forward: new slice ends one day before the next segment's start |
  | after everything (terminated tail) | revives: fresh segment open to `9999-12-31`; the gap stays |

- **Gaps are safe, silent, and deliberate.** A gap is "no coverage": as-of reads inside it return no
  row — exactly the state a not-yet-effective entity (future-dated genesis) already produces, so
  consumers carry no new obligation. Overlaps — the actual corruption class (two rows for one date) —
  remain constructively impossible: only `setEndDate` writes an end date, and only on the tail, which
  has no right neighbor. Gaps have no first-class row, so "why is it dark" lives only in the changelog;
  a domain that needs a queryable reason (suspended vs terminated, reporting rows) should model a
  **versioned status field** on top — the two compose.
- Termination is **not** deletion: the entity `id` stays valid, inbound FKs keep resolving (as-of joins
  simply return nothing past the end date), and the `onDelete` strategy does not fire.
- Sharp edges (the generic interval rules applied at a termination boundary — visible in the version
  list, one `setEndDate` away from repair): a revived segment created with no neighbor copies nothing
  (genesis-like — provide required fields); moving a revived segment's start left onto the terminated
  segment re-derives that end date (the gap is bridged); deleting the slice that carries the terminated
  end date transfers it to the predecessor (the heal rule), rather than reopening.

#### 2.7 Versioning seam (engine internals)
- All timeline handling in `ModelServiceImpl` routes through one `VersioningStrategy` seam
  (`service/versioning/`): `IdentityStrategy` is a no-op for regular models, `TimelineStrategy` adapts the
  interval-maintenance algorithm in `TimelineService`. New read paths must route Filters/FlexQuery through
  the `scopedRead` exits — there is no per-call-site `if (isTimelineModel)` to forget.
- The across-timeline opt-out is a **dual trigger by contract**: the explicit
  `FlexQuery.acrossTimelineData()` flag, **or** caller-supplied `effectiveStartDate`/`effectiveEndDate`
  conditions (which declare "I am doing my own temporal filtering"). Either suppresses the default
  effective-date clamp; both are intended, stable behavior.
- **Accepted limitations** (a master-detail table split was evaluated and rejected — its headline benefit,
  a real DB FK target, is moot because referential integrity is enforced app-level and no physical FKs are
  emitted): version-invariant fields (e.g. `code`) repeat on every slice, and a **declarative
  reference-by-code relation to a timeline model is not supported** (`code` is not physically unique across
  slices). Reference timeline entities by logical `id` (as-of) or pin one slice via `sliceId`; a **runtime**
  "`code` + effective date" as-of query is fully supported (non-overlapping intervals make it unique).
- `Context.effectiveDate` is ambient state (defaults to today). Batch engines that fan work out across
  threads must propagate the context (ScopedValue) to workers — e.g. a payroll run pricing by `payDate` —
  or that branch silently prices "as of today".

#### 2.8 search Join Rules for Timeline Associations
- When the related object is a timeline model, Many2One/One2One queries automatically append to the `LEFT JOIN ON` clause:
  `effectiveStartDate <= effectiveDate AND effectiveEndDate >= effectiveDate`.
- One2Many/Many2Many cascades also filter slices based on `effectiveDate`.

### Examples

#### 1) Model Definition
```java
@Data
@EqualsAndHashCode(callSuper = true)
@Model(label = "Product Price", timeline = true, idStrategy = IdStrategy.DISTRIBUTED_LONG)
@Index(indexName = "uk_product_price_timeline",
       fields = {"id", "effectiveStartDate", "effectiveEndDate"}, unique = true)
public class ProductPrice extends TimelineModel {
    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Product ID")
    private Long productId;

    @Field(label = "Price")               // BigDecimal → DECIMAL(32,8) by default (money)
    private BigDecimal price;
}
```

#### 2) Query Current and Historical Slices
```java
ContextHolder.getContext().setEffectiveDate(LocalDate.of(2025, 1, 1));

Filters filters = new Filters().eq("productId", 1001L);
List<Map<String, Object>> current = modelService.searchList("ProductPrice", new FlexQuery(filters));

FlexQuery historyQuery = new FlexQuery(new Filters().eq("id", 1L))
        .acrossTimelineData()
        .orderBy(Orders.ofAsc("effectiveStartDate"));
List<Map<String, Object>> history = modelService.searchList("ProductPrice", historyQuery);
```

### 3. Performance
- By default, queries do not scan across time (no `effectiveStartDate/effectiveEndDate` filters and no `acrossTimelineData()`), which reduces scanning.
- Add indexes for `effectiveStartDate` and `effectiveEndDate`.

### 4. Time-Effective (Non-Timeline) Data
Some models need history records with effective dates but are not timeline models (for example, HR changes, work history, education history). These cases may allow multiple records on the same day and do not require continuous slices.

In Softa, timeline fields are reserved. If you need history-only behavior, use a separate history model or different field names, and keep `timeline = false` to avoid timeline slice semantics.
