# Defining Entities with Softa Metadata Annotations

Part of the [Softa app authoring guide](../README.md). This is the companion for
writing the five metadata annotations — `@Model`, `@Field`, `@Index`,
`@OptionSet`, `@OptionItem`. You write annotated Java classes; the framework
creates the tables, columns, indexes, and option lists. You do **not** hand-write
DDL.

Once your entity exists, see [controllers-services.md](controllers-services.md)
to expose it over REST and [queries.md](queries.md) for read/write payload
formats.

> The authoritative reference for every attribute is the Javadoc on the
> annotation itself — in your IDE, put the cursor on `@Field(` and hover. This
> guide is the "how to put it together" companion to that.

---

## 1. One-time setup

**Add the dependency** (match the version of your other Softa starters):

```xml
<dependency>
    <groupId>io.softa</groupId>
    <artifactId>metadata-starter</artifactId>
    <version>${softa.version}</version>
</dependency>
```

**Give your app an identity and tell the scanner which packages are yours**
(`application-dev.yml`):

```yaml
system:
  app-code: my-app            # required when metadata-starter is on; fails fast at boot if missing
  metadata:
    # Which packages the scanner manages. Regex, full-matched against each
    # @Model / @OptionSet class's PACKAGE name. Dots are regex metacharacters.
    scanner-scope:
      - "io\\.acme\\.myapp.*"   # your entity packages; "*" alone = every package
```

**What happens at boot:**

| Your `scanner-scope` | What happens |
|---|---|
| `["io\\.acme\\.myapp.*"]` (dev) | The framework reads your annotations and auto-runs `CREATE TABLE` / `ADD COLUMN` / `MODIFY COLUMN` / `ADD INDEX`. It **never auto-drops** — a removed field/model/index is logged as a WARN with copy-paste SQL for you to run by hand. |
| empty / unset (production default) | No DDL runs. After boot it checks code-vs-DB and logs a WARN if they drift. Ship schema changes through your normal release process. |

Normal dev loop: **edit annotations → restart your dev app → tables update → watch the log.**

---

## 2. The five annotations

`@Model` / `@Field` / `@Index` are in `io.softa.framework.orm.annotation`.
`@OptionSet` / `@OptionItem` are in `io.softa.framework.base.annotation`.

A complete, idiomatic entity (most attributes are omitted because they equal
their defaults — see §4):

```java
import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;

@Data
@EqualsAndHashCode(callSuper = true)
@Index(fields = {"status", "createdTime"})
@Index(indexName = "uk_customer_email", fields = {"email"}, unique = true,
       message = "This email is already registered.")
@Model(label = "Customer", businessKey = {"code"})
public class Customer extends AuditableModel {

    @Field(label = "ID")
    private Long id;                          // always the PK; write @Field(label="ID"), never a fieldType

    @Field(required = true, length = 100)
    private String name;

    @Field
    private String email;

    @Field(copyable = false)                  // don't carry the natural key when duplicating a row
    private String code;

    @Field
    private CustomerTier tier;                // enum -> becomes a select (OPTION), inferred
}
```

### `@Model` (on the class)

Common attributes — omit any that equals the default:

| Attribute | Default | What it does |
|---|---|---|
| `label` | humanized class name (`DeptInfo` → "Dept Info") | display name |
| `businessKey` | `{}` | natural key field(s), e.g. `{"code"}`. Does **not** by itself create a UNIQUE index — add `@Index(... unique = true)` for that |
| `tableName` | `snake_case(modelName)` | DB table name |
| `description` | `""` | shown in UI; **≤512 chars** (parse-time enforced) — concise user-facing summary, design notes go in Javadoc |
| `softDelete` | `false` | logical delete instead of physical |
| `multiTenant` | `false` | adds a `tenant_id` scope to the table |
| `idStrategy` | `DB_AUTO_ID` | use `DISTRIBUTED_LONG` for CosID distributed IDs; `EXTERNAL_ID` for code-as-id masters |
| `copyable` | `true` | `false` = row can't be duplicated (log / runtime models) |
| `renamedFrom` | `""` | previous class name, for a safe rename — see §5 |

`modelName` is always the class simple name; there is no attribute for it.

### `@Field` (on each Java field)

Annotate **every declared field**. Most-used attributes:

| Attribute | Default | What it does |
|---|---|---|
| `label` | humanized field name (`deptId` → "Dept Id") | display name |
| `description` | `""` | shown in UI; **≤512 chars** (parse-time enforced) — concise user-facing summary, design notes go in Javadoc |
| `fieldType` | inferred from the Java type (see §3) | override only when the Java type is ambiguous |
| `length` | type default (`String` → 64, see §3) | column width; declare only to override |
| `required` | `false` (primitives auto-`true`) | NOT NULL |
| `readonly` / `unsearchable` | `false` | UI behavior |
| `copyable` | `true` | `false` = value not carried when a row is duplicated (keys, secrets, runtime state) |
| `autoSequence` | `false` | auto-fill from a sequence on INSERT when blank (document numbers) — see §5 |
| `relatedModel` | — | related model **class**, e.g. `relatedModel = Country.class`, for relations |
| `relatedField` | `""` | one-to-many: the child's FK column. To-one: leave empty (see §5) |
| `onDelete` | KEEP | what happens to referrers when the target is deleted — see §5 |
| `computed` + `expression` | `false` / `""` | derived value; see [placeholders.md](placeholders.md) |
| `renamedFrom` | `""` | previous field name, for a safe rename — see §5 |

The `id` field is always the primary key. Write `@Field(label = "ID")` on it;
**never** put a `fieldType` on `id` (it's rejected — the type follows the Java
field). Audit fields inherited from `AuditableModel` are already annotated on the
base class; don't repeat them.

### `@Index` (on the class, repeatable)

Stack as many as you need — no wrapper.

```java
@Index(fields = {"status"})                                          // non-unique
@Index(fields = {"email"}, unique = true)                            // unique, auto-named uk_...
@Index(indexName = "uk_emp_project", fields = {"empId", "projectId"},
       unique = true, message = "This employee is already on the project.")
```

- `fields` uses the **Java field names** (camelCase), not column names.
- `indexName` is optional — omit it and you get `idx_<table>_<cols>` / `uk_...`.
  Index names are globally unique and capped at 60 chars; a collision or an
  over-long name fails at boot.
- `message` is valid **only** with `unique = true`. It's the end-user text shown
  when that uniqueness is violated (a full sentence, no placeholders); it also
  serves as its own translation key.

### `@OptionSet` (on an enum class)

```java
@OptionSet(label = "Customer Tier")
public enum CustomerTier {
    @OptionItem(label = "VIP Gold") GOLD("g"),   // custom label (≠ "Gold")
    SILVER("s");                                 // bare: label defaults to "Silver"

    @JsonValue private final String code;        // this value is the stable itemCode
    CustomerTier(String code) { this.code = code; }
}
```

- Add a `@JsonValue` field so each option has a stable code (`"g"`) that crosses
  the API boundary — otherwise the code defaults to the constant name (`"GOLD"`).
- Changing an existing `@JsonValue` is a breaking API change — treat it like a
  rename, not an edit.

### `@OptionItem` (on an enum constant)

Membership is automatic — every constant becomes an option whether or not it's
annotated. Add `@OptionItem` **only** to override something inference can't give:
a custom `label` (different from the humanized name), `description`, `sequence`,
`itemTone`, or `itemIcon`. If the only effect would be `label == humanize(name)`,
leave the annotation off entirely.

---

## 3. Java type → field type (memorize this)

The framework infers the field type from the Java type. Write `fieldType` only
when the type is ambiguous.

| Java type | Inferred as | Default length | Notes |
|---|---|---|---|
| `String` | STRING | 64 | for a dropdown, make the field an **enum** — you cannot write `fieldType = OPTION` |
| `Integer` / `int` | INTEGER | — | primitive ⇒ `required = true` |
| `Long` / `long` | LONG | — | to make it a **foreign key** you MUST write `@Field(fieldType = MANY_TO_ONE, relatedModel = X.class)` |
| `Double` / `BigDecimal` | DOUBLE / BIG_DECIMAL | 24,2 / 32,8 | |
| `Boolean` | BOOLEAN | — | |
| `LocalDate` / `LocalDateTime` / `LocalTime` | DATE / DATE_TIME / TIME | — | |
| `<your enum>` | OPTION | 64 | option code = enum simple name |
| `List<enum>` | MULTI_OPTION | 255 | |
| `List<String>` | MULTI_STRING | 256 | |
| a `@Model` POJO | MANY_TO_ONE | — | related model inferred from the type |
| `List<`a `@Model` POJO`>` | ONE_TO_MANY | — | set `relatedField` = the child's FK column |
| `Long` + explicit `fieldType = FILE` | FILE | — | single attachment stored as a file id |
| `List<Long>` + explicit `fieldType = MULTI_FILE` | MULTI_FILE | 1024 | multiple attachments stored as file ids |
| `List<Long>`, `byte[]`, `Map`, raw `List` | **ambiguous** | — | you must specify `fieldType` (or the parser rejects it) |

There is no Java `File` / `MultiFile` wrapper type for annotation inference.
Use `Long` for `FILE` and `List<Long>` for `MULTI_FILE`; reads come back as
`FileInfo` / `List<FileInfo>` through the query pipeline — see
[queries.md](queries.md).

---

## 4. Two rules people trip over

**A. `OPTION` / `MULTI_OPTION` can't be written explicitly.** They are produced
*only* by inference from an enum (or `List<enum>`) Java type. Writing
`@Field(fieldType = FieldType.OPTION)` is rejected at boot, even if the Java type
matches. Want a select field? Change the Java type to the enum and drop the
`fieldType`. There is no `optionSet` attribute either — the code comes from the
enum's class name.

**B. Omit any attribute that equals its default.** A present attribute should
signal a real override; one that restates the default is noise. Concretely, omit:
- `label` when it equals the humanized name;
- booleans/enums at their default (`required = false`, `copyable = true`,
  `multiTenant = false`, `idStrategy = DB_AUTO_ID`, `unique = false`, …);
- `tableName` / `columnName` equal to `snake_case(...)`;
- `length` equal to the type default (a bare `String` is already `VARCHAR(64)`).

So the idiomatic field is `@Field private String email;`, not
`@Field(label = "Email", required = false, length = 64) private String email;`.

---

## 5. Recipes

### Add a field
```java
@Field(label = "Last Visit Date")
private LocalDate lastVisitDate;
```
Restart your dev app → the column is added automatically.

### Make a field required
Flip `required = true`. **First check for existing NULL rows** — if any exist,
backfill them before you flip it, or the tightened NOT NULL will fail.

### Remove a field safely
Delete the field. The framework will **not** drop the column automatically — it
logs a WARN with the exact `DROP COLUMN` SQL. Run that by hand once you've
confirmed nothing depends on it. (If other code still reads it, deprecate in one
release, delete in the next.)

### Add an option to an enum
Add the constant with its `@JsonValue` code. Pick a code that doesn't collide
with existing ones. Restart.

### Add a new model
Extend `AuditableModel`, annotate with `@Model`, write an explicit
`@Field(label = "ID")` on `id`, annotate the rest. If it's tenant-scoped, add
`multiTenant = true` and include `tenantId` in `businessKey`.

### Add a relation
- **Embedded (eager):** `private Country country;` — inferred `MANY_TO_ONE`.
- **FK id only (lazy):** `@Field(fieldType = MANY_TO_ONE, relatedModel = Country.class) private Long countryId;`
  (raw `Long` is ambiguous, so `fieldType` is required here).
- **One-to-many:** `@Field(fieldType = ONE_TO_MANY, relatedField = "deptId") private List<EmpInfo> employees;`
  (`relatedField` = the FK column on the child).

**A to-one relation always joins on the target's `id`** — leave `relatedField`
empty (a non-id value is rejected at boot). If you want the FK to *store a
portable business code* (e.g. `"USD"`) instead of an opaque number, the target
master must use its code AS its id: `@Model(idStrategy = EXTERNAL_ID)` with a
`String id` that is the code. Then:
```java
@Field(fieldType = FieldType.MANY_TO_ONE, relatedModel = Currency.class)
private String defaultCurrency;   // stores "USD"; still joins on Currency.id
```

### Control what happens on delete (`onDelete`)
Put it on the **foreign-key** side (the to-one field), never on the one-to-many:
- `RESTRICT` — block the delete if referrers exist.
- `CASCADE` — delete the referrers too.
- `SET_NULL` — null out the FK (hard-delete only; the FK must be nullable).
- omitted = do nothing.

Enforced by the framework in application code (no physical DB foreign key). Some
combinations are rejected at boot on purpose — e.g. `SET_NULL` on a `required`
FK, or a `CASCADE` that could delete recoverable data or fan across tenants. The
boot error explains which and why.

### Auto-number a field from a sequence (`autoSequence`)
For business document numbers ("EMP-00042", "SO-2026-00007"):

```java
@Field(label = "Employee No", autoSequence = true, readonly = true)
private String employeeNo;
```

- **STRING fields only** (the rendered number is a string); rejected on `id`,
  `computed`, `dynamic`, and non-RDBMS models.
- The number format lives in a **`sys_sequence` row** whose `code` is
  `"<ModelName>.<fieldName>"` (e.g. `"Employee.employeeNo"`), provisioned per
  tenant via `loadPreTenantData`. The row holds the template
  (`EMP-{yyyy}-{seq:5}`), reset cadence, and gap policy. **The row must exist**
  — an insert with no matching row fails with `SequenceNotFoundException`
  (fail-closed; there is no silent fallback).
- **With `readonly = true`** (recommended): strict system numbering — callers
  can never hand-assign the value. **Without it**: blank values are filled,
  caller-provided values are trusted (useful for data imports).
- Pair with a **unique index** on the field if numbers must be unique — the
  sequence guarantees allocation order, the index guarantees uniqueness.
- Duplicating a row never copies the number; the copy gets a fresh one on
  insert.

### Rename a field or model
**Declare the rename** with `renamedFrom` — don't just change the name. Without
it, a rename looks like "drop old + add new", so you'd get a new empty column and
the old data stranded.

```java
@Field(label = "External ID", renamedFrom = "legacyId")   // was legacyId
private Long externalId;
```
```java
@Model(renamedFrom = "OldCustomer")   // class was OldCustomer
public class Customer extends AuditableModel { ... }
```
The framework issues a `CHANGE COLUMN` / `RENAME TABLE` and moves the data in
place. It's **single-step** — it holds only the immediately-previous name. A
multi-hop rename (A→B→C where some environment is still on A) needs a
hand-written migration instead.

Renaming an `autoSequence` field (or its model) has one extra step: the
binding code `"<Model>.<field>"` stored in the per-tenant `sys_sequence` rows
does **not** follow automatically. The boot log prints the exact
`UPDATE sys_sequence SET code = ...` to run; until it runs, inserts on that
field fail with `SequenceNotFoundException`.

### Add an index
See `@Index` in §2. `fields` are Java field names; restart to apply.

### Define a timeline (effective-dated) model
For data queried "as of a date" (org charts, rate tables, salary profile items — contiguous
versions, never edited in place). Extend `TimelineModel` (brings `sliceId` + the effective
dates), set `timeline = true`, and **always** declare both of these:

```java
@Model(label = "Rate Table", businessKey = {"code"}, timeline = true,
        idStrategy = IdStrategy.DISTRIBUTED_LONG)   // REQUIRED: DB_AUTO_ID is boot-rejected —
                                                    // the auto-increment lands on sliceId, so the
                                                    // shared logical `id` must be app-generated
@Index(indexName = "uk_rate_table_timeline",        // explicit name: the default concatenation
        fields = {"id", "effectiveStartDate", "effectiveEndDate"}, unique = true)
public class RateTable extends TimelineModel { ... }
```

The unique index is the standard timeline pattern — it covers the as-of read and turns a
concurrent same-start write race into a loud unique violation. Rules of thumb: other models
reference a timeline model by its logical `id` (resolved as-of `Context.effectiveDate`) or pin
one version via `sliceId`; a declarative reference-by-code to a timeline model is not supported
(`code` repeats per slice) — use a runtime "`code` + effective date" query instead. Reads default
to the current effective date; use `FlexQuery.acrossTimelineData()` (or filter on the effective
dates yourself) for history. `deleteById` removes the whole entity (all slices, fires `onDelete`);
`deleteBySliceId` removes one version (never fires `onDelete`).

---

## 6. Check your change

```bash
mvn -q -DskipTests compile     # catches type/import errors and wrong relatedModel classes
mvn -q test                    # your app's own tests
git diff                       # read it — confirm intent
```

Then boot your dev app (with your package in `scanner-scope`) and watch the log:
```
MetadataAnnotationScanner: applied N row change(s)
DdlOrchestrator: CREATE TABLE customer OK          # auto-executed
DdlOrchestrator: ... DROP COLUMN ... not auto-executed   # WARN — run by hand
```

---

## 7. Common mistakes

1. **`@Field(fieldType = OPTION / MULTI_OPTION)`** — rejected. Use an enum type.
2. **A raw `Long` FK without `fieldType`** — ambiguous; write
   `fieldType = MANY_TO_ONE, relatedModel = X.class`.
3. **`fieldType` on `id`** — rejected; the PK type follows the Java field.
4. **Restating defaults** (`required = false`, `multiTenant = false`, …) — noise; omit them.
5. **Renaming without `renamedFrom`** — strands your data. Always declare it.
6. **Forgetting `@JsonValue` on a new enum** — option codes silently become the constant names.
7. **A `message` on a non-unique `@Index`** — only valid with `unique = true`.
8. **Expecting a UNIQUE constraint from `businessKey`** — it doesn't create one;
   add an explicit `@Index(..., unique = true)`.
