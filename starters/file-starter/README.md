# File Starter

File Starter provides four core capabilities for developers:
- [Data import](./import)
- [Data export](./export)
- [Document export (Word/PDF)](./document)
- Document signing

This document focuses on developer usage and API-level examples.

## Code Structure

- `excel/export/strategy`: export strategy selection and concrete export implementations
- `excel/export/support`: shared export support components such as data fetch, template resolve, writer, upload, and custom export hooks
- `excel/imports`: import pipeline, handler factory, failure collection, persistence, and custom import hook
- `excel/style`: shared Excel style handlers
- `pdf/`: PDF document generators, Noto font provider, and PDF signing helpers (Word, PDF, signing)
- `word/`: Word document generator

## Dependency
```xml
<dependency>
  <groupId>io.softa</groupId>
  <artifactId>file-starter</artifactId>
  <version>${softa.version}</version>
</dependency>
```

## Requirements
- OSS storage (Minio or other supported providers) for template files and generated files.
- Pulsar is required if you use async import.
- Noto fonts are required for PDF generation (RICH_TEXT templates). Run `sh deploy/install-font.sh` to install.
- Database contains file metadata tables and file-starter tables:
  - Import: ImportTemplate, ImportTemplateField, ImportHistory,
  - Export: ExportTemplate, ExportTemplateField, ExportHistory,
  - Document: DocumentTemplate,
  - Signing: SigningRequest, SigningDocument.

## Configuration
### MQ topics (async import)
```yml
mq:
  topics:
    async-import:
      topic: dev_demo_async_import
      sub: dev_demo_async_import_sub
```

### OSS Configuration
```yml
oss:
  type: minio
  endpoint: http://minio:9000
  access-key: minioadmin
  secret-key: minioadmin
  bucket: dev-demo
```

### Storage Policy
- General path: `modelName/uuid/fileName`
- Multi-tenancy path: `tenantId/modelName/uuid/fileName`

## A. Data Import
File Starter supports two import modes:
1. Import by configured template (ImportTemplate + ImportTemplateField)
  - Import by configured template (ImportTemplate + ImportTemplateField)
  - supports template download
  - submits uploaded files through the configured template

2. Dynamic mapping import (no template, mapping provided in request)
  - Dynamic mapping import (no template, mapping provided in request)
  - parses the uploaded `.xlsx` workbook in the browser
  - auto-maps workbook headers to model fields using metadata
  - lets the user adjust mappings before submit

### ImportTemplate Configuration Table
| Field | Type | Default | Description |
| --- | --- | --- | --- |
| `name` | String | `null` | Template name |
| `modelName` | String | `null` | Model name to import |
| `importRule` | ImportRule | `null` | Import rule: CreateOrUpdate / OnlyCreate / OnlyUpdate |
| `uniqueConstraints` | List<String> | `null` | Unique key fields used by CreateOrUpdate |
| `ignoreEmpty` | Boolean | `null` | Ignore empty values when importing |
| `skipException` | Boolean | `null` | Continue when a row fails |
| `customHandler` | String | `null` | Spring bean name for CustomImportHandler |
| `syncImport` | Boolean | `null` | If true, import runs synchronously; otherwise async |
| `includeDescription` | Boolean | `null` | Whether to include description in template output |
| `description` | String | `null` | Description text |
| `importFields` | List<ImportTemplateField> | `null` | Import field list |

### ImportTemplateField Configuration Table
| Field | Type | Default | Description |
| --- | --- | --- | --- |
| `templateId` | Long | `null` | ImportTemplate id |
| `fieldName` | String | `null` | Model field name (supports `deptId.code` relation lookup) |
| `customHeader` | String | `null` | Custom Excel header |
| `sequence` | Integer | `null` | Field order in template |
| `required` | Boolean | `null` | Required field |
| `defaultValue` | String | `null` | Default value (supports `{{ expr }}`) |
| `description` | String | `null` | Description text |

### 1. Import By Template (Configured)
1. Configure ImportTemplate and ImportTemplateField

ImportTemplate key fields:
- `name`, `modelName`, `importRule`
- `uniqueConstraints` (for CreateOrUpdate)
- `ignoreEmpty`, `skipException`, `customHandler`, `syncImport`

ImportTemplateField key fields:
- `fieldName`, `customHeader`, `sequence`, `required`, `defaultValue`

Notes:
- Default values in ImportTemplateField support placeholders `{{ expr }}`. Simple variables are resolved from `env`, and expressions are evaluated against `env`.
- If `syncImport = true`, import is executed in-process.
- If `syncImport = false`, an async import message is sent to MQ.

Required columns — what a blank cell (with no `defaultValue`) does:

| Column is | `ignoreEmpty = false` | `ignoreEmpty = true` |
| --- | --- | --- |
| `required` on the template | Refused by the importer | Refused by the importer |
| Required on the model only | Refused by the importer (it would be written as null) | Dropped from the row; the ORM refuses a **create** without it, an **update** keeps the stored value |
| Neither | Written as null | Dropped from the row |

- A cell cannot tell a create from an update, so with `ignoreEmpty = true` the importer leaves the model's requiredness to the ORM, which can. That is what lets a file of corrections leave a mandatory column blank to mean "no change". The template's own `required` is a rule for every row and is never relaxed.
- A create the ORM refuses for a missing value is reported with the importer's own message, naming the column (`The field \`Company Name\` is required`), not the model field.
- The same rule applies to a relation lookup column (`deptId.code`) on a required relation. A OneToOne sub-field never inherits the sub-model's requiredness (see 1.1.1).

### 1.1 Relation Lookup Import (Cascaded Import)
The `fieldName` in ImportTemplateField (or `importFieldDTOList` in dynamic import) supports **dotted-path relation lookup** via `RelationLookupResolver`. Instead of importing a raw FK id, you can import a human-readable business key of the related model, and the system will reverse-lookup the FK id automatically.

**Syntax:** `{fkField}.{businessKey}` — e.g. `deptId.code`, `deptId.name`

> ⚠️ **A dotted path means two different things, decided by the root field's type.** A **ManyToOne /
> to-many** root points at a row that already exists and is shared, so the dotted columns are a
> *business key to look it up by* — described below. A **OneToOne** root is the main row's *own* 1:1
> sub-record, so its dotted columns are that sub-record's *content*, written inline — see
> [1.1.1 Nested OneToOne Import](#111-nested-onetoone-import-cascade-write). Looking a OneToOne group
> up would be meaningless (no row matches "every attribute equal") and fails on the first blank cell,
> since a business key may not contain nulls.

**How it works (ManyToOne / to-many roots):**
1. The system detects dotted-path fields whose root is a ManyToOne or to-many field.
2. Groups them by root FK field (e.g. `deptId.code` and `deptId.name` form one group).
3. Batch-queries the related model by the business key values to resolve FK ids.
4. Writes back the resolved FK id to the root field (`deptId`) and removes the dotted-path columns.

**Rules:**
- Only **single-level** cascade is supported: `deptId.code` ✅, `deptId.companyId.code` ❌
- A direct FK field (e.g. `deptId`) and a lookup field (e.g. `deptId.code`) **must not coexist** in the same template.
- Multiple lookup fields sharing the same root are combined as a composite business key (e.g. `deptId.code` + `deptId.name` together uniquely identify a Department).
- **Country-partitioned targets resolve in the row's own country.** "Full Time" names a different Employment Type in every country that has one, so a business key on a `@Model(multiCountry)` target is only unique within a country — and the importer's own countries are the wrong domain when one file carries both Singapore and New Zealand employees. `RelationLookupResolver` resolves the row's company reference first (the importing model's field onto `Company` — `legalEntityId` on an employee, `companyId` on a department — whatever the column order), turns it into a country (`CompanyCountryResolver`, cached), buckets the rows by it and queries each bucket with `country = X` on top of the field's own filters. Rows naming no company resolve as before, within the caller's countries. A template that looks a partitioned target up by name without carrying a company column logs a warning at detection time (`partitionedLookupsWithoutCompanyColumn` exposes the same list for a template editor): it works for a single-country importer and fails the row as a duplicate key for a multi-country one.
- When all lookup values in a row are empty:
  - If `ignoreEmpty = true`: the FK field is skipped (not written).
  - If `ignoreEmpty = false`: the FK field is explicitly set to `null`.
- When a lookup fails (no matching record found):
  - If `skipException = true`: the row is marked as failed with a reason message.
  - If `skipException = false`: a `ValidationException` is thrown immediately.

#### 1.1.1 Nested OneToOne Import (Cascade Write)

When the root field is **OneToOne**, the related row belongs to the row being imported — `Employee.employeeProfileId` is *this* employee's profile, not a shared one to be found. The dotted columns are therefore folded into a **nested value object** on the root field, which the ORM write pipeline (`XToOneGroupProcessor#processNestedOneToOneRows`) creates or updates inline, in the same transaction.

**Syntax:** identical — `{oneToOneField}.{subField}`, e.g. `employeeProfileId.gender`

**How it works:**
1. Standard field handlers run first, keyed by the dotted path, so sub-values get the same type conversion / option mapping as flat columns (dates parsed, options mapped).
2. Non-blank cells are collected into a nested map under the root field; the dotted columns are removed.
3. On **create**, the sub-record is created and its new id linked back into the FK.
4. On **update** via `createOrUpdate`, the sub-record the main row already points at is reused (its id is read back and carried into the nested object), so the existing row is updated in place rather than replaced and orphaned.

**Rules:**
- **Blank means "keep the existing value"** — blank cells are left out of the nested object, so an update never blanks a field the file did not fill in.
- An **all-blank** group still produces an *empty* object rather than nothing: the sub-record belongs to the main row, so a create must still produce one (the owning FK is typically `required`) and an update simply relinks the existing sub-row.
- `ignoreEmpty` does not apply — it describes whether to null a *reference*, which a sub-record is not.
- No lookup is issued, so there is no "not found" failure mode. Validation errors from the sub-record surface per row like any other write error.

**Example — Template-based import:**

ImportTemplateField configuration:
```
fieldName: "deptId.code"    customHeader: "Department Code"    sequence: 3
fieldName: "name"           customHeader: "Employee Name"      sequence: 1
fieldName: "jobTitle"       customHeader: "Job Title"          sequence: 2
```

Excel file:
| Employee Name | Job Title | Department Code |
| --- | --- | --- |
| Alice | Engineer | D001 |
| Bob | Manager | D002 |

The system will look up `Department` by `code = "D001"` / `"D002"`, resolve the `id`, and write it into `deptId`.

**Example — Dynamic import with relation lookup:**
```bash
curl -X POST http://localhost:8080/import/dynamicImport \
  -F file=@/path/to/employees.xlsx \
  -F 'wizard={
    "modelName":"Employee",
    "importRule":"CreateOrUpdate",
    "uniqueConstraints":"employeeCode",
    "importFieldDTOList":[
      {"header":"Employee Name","fieldName":"name","required":true},
      {"header":"Department Code","fieldName":"deptId.code","required":true},
      {"header":"Job Title","fieldName":"jobTitle"}
    ],
    "syncImport":true
  };type=application/json'
```

2. Download the template file (optional)

Endpoint:
- `GET /ImportTemplate/getTemplateFile?id={templateId}`

The generated template uses field labels as headers. Required headers are styled.

3. Import by template

Endpoint:
- `POST /import/importByTemplate`

Parameters:
- `templateId`: ImportTemplate id
- `file`: Excel file
- `env`: JSON string for environment variables

Example:
```bash
curl -X POST http://localhost:8080/import/importByTemplate \
  -F templateId=1001 \
  -F env='{"deptId": 10, "source": "manual"}' \
  -F file=@/path/to/import.xlsx
```

### 2. Dynamic Mapping Import (No Template)
Endpoint:
- `POST /import/dynamicImport`

This endpoint accepts a `multipart/form-data` payload with:
- `file`: uploaded Excel file
- `wizard`: JSON payload for `ImportWizard`

Key fields:
- `modelName`
- `importRule`: `CreateOrUpdate` | `OnlyCreate` | `OnlyUpdate`
- `uniqueConstraints`: comma-separated field names
- `importFieldDTOList`: header-to-field mappings
- `ignoreEmpty`, `skipException`, `customHandler`, `syncImport`

Example:
```bash
curl -X POST http://localhost:8080/import/dynamicImport \
  -F file=@/path/to/import.xlsx \
  -F 'wizard={
    "modelName":"Product",
    "importRule":"CreateOrUpdate",
    "uniqueConstraints":"productCode",
    "importFieldDTOList":[
      {"header":"Product Code","fieldName":"productCode","required":true},
      {"header":"Product Name","fieldName":"productName","required":true},
      {"header":"Price","fieldName":"price"}
    ],
    "syncImport":true
  };type=application/json'
```

### 3. Import Result and Failed Rows
- Import returns `ImportHistory`.
- If any row fails, a “failed data” Excel file is generated and saved, with a `Failed Reason` column.
- Import status can be `PROCESSING`, `SUCCESS`, `FAILURE`, `PARTIAL_FAILURE`; a validation run
  (§5) records `VALIDATION_SUCCESS` / `VALIDATION_FAILURE` instead and attaches a result Excel
  containing **all** rows, passed and failed.
- Failure is per row, not all-or-nothing: rows marked with `Failed Reason` are removed from the batch
  and the rest are written (`PARTIAL_FAILURE`). Only `skipException = false` makes the first bad row
  abort the whole import.
- Every typed column is converted **before** the write, so a bad cell is reported with its column name
  (`The Integer field 'Sequence' is incorrect 'abc'`). What reaches the ORM unconverted comes back as
  the JDBC/JDK message instead — that was the case for numeric and FK columns until `NumberHandler` /
  `RelationIdHandler` were added, and `For input string: "Branch / Branch"` named nothing at all.
- A relation column mapped by its **bare field name** imports the related row's id. When that id is
  numeric, a non-numeric cell is refused with the lookup path it should have used
  (`… map this column to 'orgType.itemCode'`) — the mistake is easy to make because the value people
  paste is what the detail page shows, and `displayName` joins fields with `" / "`. A code-as-id master
  (`CountryRegion`, `Currency`) is exempt: its id IS the portable code, so a bare column is correct
  there.
- An **option** or **boolean** column takes either the stored code or the label a person sees, in any
  casing — `Technology` or its label, `true` / `false` or `Yes` / `No`. This matters because an export
  writes the label, and an exported file is what people re-import: a column that only accepted the
  stored code would refuse the file the product just produced. Nothing else is accepted; `1` / `0` is
  not a boolean spelling here.

### 4. Custom Import Handler
You can register a Spring bean implementing `CustomImportHandler` and reference it by name in
`ImportTemplate.customHandler` or `ImportWizard.customHandler`.

```java
import io.softa.starter.file.excel.imports.CustomImportHandler;

@Component("productImportHandler")
public class ProductImportHandler implements CustomImportHandler {
    @Override
    public void handleImportData(List<Map<String, Object>> rows, Map<String, Object> env,
                                boolean validateOnly) {
        // custom preprocessing — always runs
        if (!validateOnly) {
            // side effects only: provisioning, outbound calls, sequence allocation
        }
    }
}
```

Contract:
- You may update row values in place.
- You may mark a row failed by writing `FileConstant.FAILED_REASON`.
- Do not add, remove, reorder, or replace row objects.
- Your **checks** run in both modes — they are part of the feedback a validation run produces. Your
  **side effects** must be skipped when `validateOnly` is true; the pipeline can withhold its own
  persistence, not yours.

The two-arg `handleImportData(rows, env)` overload is `@Deprecated(forRemoval = true)` and delegates
with `false`. It exists so an existing implementation still compiles; the pipeline never calls it.

### 5. Import vs validate-only — one pipeline, two modes

`/import/validateImport` is a dry run: same checks, nothing written. Both entry points funnel through
the same private `ImportRowPipeline.processRows`, which takes an `ImportMode`:

```
importByTemplate / dynamicImport → syncImport   → importData   → processRows(IMPORT)        → persist
validateImport                   → syncValidate → validateData → processRows(VALIDATE_ONLY)   (no write)
```

`ImportMode` does **not** control whether rows are validated. Field handlers, relation lookup, unique
constraints, the custom handler and failure collection run identically in both modes. The mode is purely
subtractive: `VALIDATE_ONLY` skips `persist`, and is passed to the custom handler so it can skip its own
side effects. Two consequences worth knowing:

- **Sync vs async is a different axis.** `ImportTemplate.syncImport` decides who runs the import (the
  request thread, or an MQ consumer / `@Async`); the async consumer calls the very same `syncImport`
  method. Neither mode nor validation differs between them. The method name means "run an import
  synchronously", not "the sync-mode import".
- **A validation run cannot see model-level errors.** Required fields, type and model constraints are
  raised by `ModelService` at write time, which `VALIDATE_ONLY` never reaches. A file that validates
  clean can still fail per row on a real import.

#### Why the mode is a parameter and not a field on `ImportTemplateDTO`

`skipException`, `importRule`, `uniqueConstraints` all live on that DTO, so the mode looks like it
belongs there too. It does not, for four reasons:

| | |
|---|---|
| **It is not configuration** | Every other field on the DTO has an origin in the `import_template` row (or the wizard's equivalent input). The mode is "which endpoint the caller invoked" — there is no column for it and there should not be. Mixing the two means a reader can no longer tell stored config from per-call intent. |
| **The DTO is the MQ message** | It carries `Context` across the async-import hop. A mode field becomes wire surface for a path where only `IMPORT` is ever legal — and a stray `VALIDATE_ONLY` there would make the consumer write nothing while `import_history` reports success. |
| **A mutable field needs a save/restore dance** | `validateData` already has to set and restore `skipException`. Doing the same for the mode buys the worst failure class available: a stale `VALIDATE_ONLY` silently writes zero rows and still reports success. As an argument it cannot leak between calls. |
| **One decision, one reader** | `persist` is called by `importData`, not from inside `processRows`. As a field the mode would be read in two places to answer one question; as an argument each call site simply states what it is doing. |

The consistent alternative, if it is ever wanted, runs the other way: make the per-run options
(`skipException` included) immutable on the DTO, fixed once at construction. That removes the
save/restore in `validateData` — and only then does the mode have a coherent place to sit alongside them.

## B. Data Export
File Starter supports three export modes:
1. Export by template fields
  - builds candidate fields from current model metadata
  - defaults selected fields to the currently visible table columns
  - lets the user change fields, file name, and sheet name
  - generates `.xlsx` workbooks for front-end initiated exports
2. Export by file template
    - uses an uploaded Excel template file with `{{ field }}` placeholders
    - extracts variables from the template to determine which fields to query
    - generates `.xlsx` workbooks by rendering the template with data

3. Dynamic export
  - exports without a template by providing fields and filters directly in the request

Built-in export supports three scopes:
- `Selected Rows` uses the current toolbar bulk selection ids
- `Current Page` uses the current page id snapshot, not `pageNumber/pageSize` replay
- `All Filtered Data` reuses current `filters/orders/groupBy/aggFunctions/effectiveDate`

Front-end export is limited to `100000` records for a single request; over-limit scopes are disabled instead of truncated.

### ExportTemplate Configuration Table

### Pivot columns on a dynamic export

`ExportParams.pivot` (a `PivotSpec`) appends one column per key of a related long table — the FTE report's "one column per employment type". The list view renders its pivot from the same declaration, so the sheet and the screen agree by construction: the column set is the key model's whole domain (read through `searchName`, already narrowed to the caller's countries), ordered by group then label and suffixed with the group when the client asks (`Full Time (SG)`); the cell is the source row's value, else `0` when the column's group applies to the row and `—` (`emptyText`) when it does not. The exported rows' group is read through `rowGroupField` — a to-one whose target carries `keyModelGroupField` — in a second, non-display read, because the export rows themselves come back with display names that cannot be joined on. Nothing is stored in pivot shape. See `PivotColumns`.

| Field | Type | Default | Description |
| --- | --- | --- | --- |
| `fileName` | String | `null` | Export file name |
| `sheetName` | String | `null` | Sheet name |
| `modelName` | String | `null` | Model name to export |
| `customFileTemplate` | Boolean | `null` | If true, use file template export mode; otherwise use field template mode |
| `fileId` | Long | `null` | Template file id (required when `customFileTemplate = true`) |
| `filters` | Filters | `null` | Default filters |
| `orders` | Orders | `null` | Default orders |
| `customHandler` | String | `null` | Spring bean name for CustomExportHandler |
| `enableTranspose` | Boolean | `null` | Whether to transpose output (not implemented in starter) |

### ExportTemplateField Configuration Table
| Field | Type | Default | Description |
| --- | --- | --- | --- |
| `templateId` | Long | `null` | ExportTemplate id |
| `fieldName` | String | `null` | Model field name (supports cascaded fields like `deptId.name`) |
| `customHeader` | String | `null` | Custom column header |
| `sequence` | Integer | `null` | Field order in export |
| `ignored` | Boolean | `null` | Whether to ignore the field in output |

### Cascaded Field Export
All three export modes support **cascaded field references** using dotted-path syntax (e.g. `deptId.name`, `deptId.companyId.code`).
This allows exporting fields from related models through ManyToOne/OneToOne associations.

**Syntax:** `{field1}.{field2}` or `{field1}.{field2}.{field3}` (up to 4 levels of cascade)

**How it works:**
1. The ORM layer creates dynamic virtual fields for dotted-path references (via `MetaField.createDynamicField`).
2. The field is split into 2 parts: the root ManyToOne/OneToOne field and the remaining path.
3. The related model is queried with the remaining path as expand fields.
4. For 3+ levels, the process recurses: `deptId.companyId.code` → query `Dept` with field `companyId.code` → query `Company` with field `code`.
5. The resolved value is stored in the row map with the full dotted key (e.g. `deptId.companyId.code`).
6. The column header defaults to the **last field's label** (e.g. the label of `code`), unless `customHeader` is set.

**Rules:**
- Maximum cascade depth: **4 levels** (`BaseConstant.CASCADE_LEVEL = 4`).
- Each intermediate field must be **ManyToOne or OneToOne** type.
- The last field must be a **stored field** (not dynamic/computed).
- `ConvertType.DISPLAY` is used, so option fields show `label` and relation fields show `displayName`.

**Example — Template export with cascaded fields:**

ExportTemplateField configuration:
```
fieldName: "name"                customHeader: null           sequence: 1
fieldName: "deptId.name"         customHeader: "Department"   sequence: 2
fieldName: "deptId.managerId.name" customHeader: "Dept Manager" sequence: 3
```

**Example — Dynamic export with cascaded fields:**
```bash
curl -X POST 'http://localhost:8080/export/dynamicExport?modelName=Employee' \
  -H 'Content-Type: application/json' \
  -d @- <<'JSON'
{
  "fields": ["name", "deptId.name", "deptId.managerId.name"],
  "filters": ["status", "=", "ACTIVE"],
  "orders": ["name", "ASC"]
}
JSON
```

Result Excel columns: `Name | Department | Dept Manager`

### 1. Export By Template Fields
1. Configure ExportTemplate and ExportTemplateField

ExportTemplate key fields:
- `fileName`, `sheetName`, `modelName`
- `filters`, `orders`, `customHandler`

ExportTemplateField key fields:
- `fieldName`, `customHeader`, `sequence`, `ignored`

2. Export by template

Endpoint:
- `POST /export/exportByTemplate?exportTemplateId={id}`

Request body:
- `ExportParams` (fields, filters, orders, agg, groupBy, limit, effectiveDate)

Example:
```bash
curl -X POST http://localhost:8080/export/exportByTemplate?exportTemplateId=2001 \
  -H 'Content-Type: application/json' \
  -d @- <<'JSON'
{
  "fields": ["id", "name", "code", "status"],
  "filters": ["status", "=", "ACTIVE"],
  "orders": ["createdTime", "DESC"],
  "limit": 200,
  "groupBy": [],
  "effectiveDate": "2026-03-03"
}
JSON
```

### 2. Export By File Template (Upload Template File)
This mode uses an uploaded Excel template file with placeholders like `{{ field }}` or `{{ object.field }}`.
The system extracts variables from the template to decide which fields to query.

To use this mode, set `customFileTemplate = true` and `fileId` to the uploaded template file in ExportTemplate.
The same `exportByTemplate` endpoint is used; the system dispatches to file-template mode automatically based on the `customFileTemplate` flag.

Endpoint:
- `POST /export/exportByTemplate?exportTemplateId={id}`

Example:
```bash
curl -X POST http://localhost:8080/export/exportByTemplate?exportTemplateId=2002 \
  -H 'Content-Type: application/json' \
  -d @- <<'JSON'
{
  "filters": ["status", "=", "ACTIVE"],
  "orders": ["createdTime", "DESC"],
  "limit": 200
}
JSON
```

**File template placeholder syntax:**
- `{{ fieldName }}` — replaced with the field value of each row
- `{{ deptId.name }}` — cascaded field reference (resolved by the ORM layer)
- The `{{ }}` syntax is normalized to underlying Fesod `{}` syntax before rendering

### 3. Dynamic Export
Export without a template by providing fields and filters directly.

Endpoint:
- `POST /export/dynamicExport?modelName={model}&fileName={fileName}&sheetName={sheetName}`

Example:
```bash
curl -X POST 'http://localhost:8080/export/dynamicExport?modelName=Product&fileName=Products&sheetName=Sheet1' \
  -H 'Content-Type: application/json' \
  -d @- <<'JSON'
{
  "fields": ["id", "name", "code", "status"],
  "filters": ["status", "=", "ACTIVE"],
  "orders": ["createdTime", "DESC"],
  "limit": 200,
  "groupBy": [],
  "effectiveDate": "2026-03-03"
}
JSON
```

### 4. Custom Export Handler
You can register a Spring bean implementing `CustomExportHandler` and reference it by name in
`ExportTemplate.customHandler`.

```java
import io.softa.starter.file.excel.export.support.CustomExportHandler;

@Component("productExportHandler")
public class ProductExportHandler implements CustomExportHandler {
    @Override
    public void handleExportData(List<Map<String, Object>> rows) {
        // custom post-processing
    }
}
```

Contract:
- You may update row values in place.
- You should not replace row map objects.

## C. Document Export (Word/PDF)
Document templates are stored in `DocumentTemplate` and rendered as Word or PDF.

### DocumentTemplate Configuration Table
| Field          | Type | Default | Description |
|----------------| --- | --- | --- |
| `modelName`    | String | required | Model name to fetch data |
| `fileName`     | String | required | Output file name |
| `templateType` | DocumentTemplateType | `WORD` | `WORD`, `RICH_TEXT`, or `PDF` |
| `fileId`       | Long | `null` | Template file id (required for WORD type) |
| `htmlTemplate`  | String | `null` | HTML with `{{ }}` placeholders (required for RICH_TEXT type) |
| `convertToPdf` | Boolean | `null` | Convert WORD output to PDF if true |
| `description`  | String | `null` | Description text |

### Template Types and Generation Pipeline

```
templateType = WORD
  1. Extract variables from .docx via poi-tl (skip # and > plugin tags)
  2. Build SubQueries for OneToMany fields (LoopRowTableRenderPolicy)
  3. Fetch data: modelService.getById(modelName, rowId, fields, subQueries, ConvertType.DISPLAY)
  4. Render .docx via poi-tl (WordFileGenerator)
  5. If convertToPdf=true, convert DOCX to PDF via docx4j
  6. Upload to OSS -> return FileInfo

templateType = RICH_TEXT
  1. Extract {{ }} variables from htmlTemplate (HTML) via PlaceholderUtils
  2. Build SubQueries for OneToMany fields
  3. Fetch data: modelService.getById(modelName, rowId, fields, subQueries, ConvertType.DISPLAY)
  4. Convert {{ }} are rendered to the final HTML via Pebble
  5. Convert HTML to PDF via OpenHTMLToPDF
  6. Upload to OSS -> return FileInfo
```

### WORD Template Syntax
- Uses `{{ variable }}` placeholder syntax.
- Use `{{#fieldName}}` for OneToMany fields rendered as looping table rows via `LoopRowTableRenderPolicy`.
- OneToMany fields are auto-detected from model metadata; SubQueries are built automatically to load related data.

### RICH_TEXT Template
- `htmlTemplate` stores HTML with `{{ variable }}` placeholders.
- Placeholders are rendered to the final HTML via Pebble.
- The rendered HTML is converted to PDF via OpenHTMLToPDF.

### Endpoint
- `GET /DocumentTemplate/generateDocument?templateId={id}&rowId={rowId}`

Example:
```bash
curl -X GET 'http://localhost:8080/DocumentTemplate/generateDocument?templateId=3001&rowId=10001'
```

### Programmatic API
Besides the REST endpoint (which fetches data by `modelName` + `rowId`), you can also call `DocumentTemplateService` directly with a custom data map:

```java
@Autowired
private DocumentTemplateService documentTemplateService;

// Option 1: Generate by rowId (fetches data from the model automatically)
FileInfo fileInfo = documentTemplateService.generateDocument(templateId, rowId);

// Option 2: Generate by custom data map
Map<String, Object> data = Map.of(
    "name", "Alice",
    "deptId", "Engineering",
    "orderItems", List.of(
        Map.of("productName", "Widget", "quantity", 10),
        Map.of("productName", "Gadget", "quantity", 5)
    )
);
FileInfo fileInfo = documentTemplateService.generateDocument(templateId, data);
```

The `generateDocument(templateId, data)` overload skips the model data fetch step and renders the template directly with the provided `Map<String, Object>`. This is useful when:
- The data comes from an external source or custom aggregation.
- You want to render a document from a non-model data structure.

## D. Document Signing
File Starter also provides a lightweight signing flow built on top of `SigningRequest` and `SigningDocument`.

### Signing Model
- `SigningRequest`: signing transaction header, recipient, status, expiration time, and related `SigningDocument` list.
- `SigningDocument`: one signable document under a signing request.

Current implementation stores a compact set of top-level fields on `SigningDocument`:
- business fields: `signingRequestId`, `templateId`, `signSlotCode`, `status`
- generated file fields: `signedImageId`, `signedPdfId`
- signer fields: `signerUserId`, `signerName`, `signedAt`
- audit correlation: `evidenceId`
- full evidence payload: `signatureEvidence` (`JsonNode`)

`signatureEvidence` contains:
- `evidenceId`
- `signSlotCode`
- `clientPayload`
- `resolvedPlacement`
- `resolvedRenderOptions`
- `serverEvidence`
  - `signatureMethod`
  - `signerUserId`, `signerName`
  - `serverSignedAt`
  - `clientIp`, `userAgent`
  - `signatureImageFileId`, `generatedSignedFileId`, `originalTemplateFileId`
  - `originalPdfSha256`, `signatureImageSha256`, `signedPdfSha256`

### Status
`SigningRequestStatus`:
- `Draft`
- `Sent`
- `InProgress`
- `Completed`
- `Cancelled`
- `Expired`

`SigningDocumentStatus`:
- `Pending`
- `InProgress`
- `Completed`

### Sign Endpoint
Endpoint:
- `POST /SigningDocument/sign?id={id}`

Content type:
- `multipart/form-data`

Parts:
- `signatureFile`: handwritten signature image file, usually PNG
- `payload`: JSON payload of `SigningDocumentSignRequest`

Request DTO:
```json
{
  "signSlotCode": "EMPLOYEE_SIGN",
  "placement": {
    "page": 1,
    "x": 120,
    "y": 90,
    "width": 180,
    "height": 64,
    "unit": "PT"
  },
  "evidence": {
    "signatureMethod": "DRAW",
    "clientSignedAt": "2026-03-24T10:15:30+08:00",
    "clientTimeZone": "Asia/Shanghai",
    "consentAccepted": true,
    "consentTextVersion": "v1",
    "signerDisplayName": "Alice",
    "userAgent": "Mozilla/5.0",
    "canvasWidth": 800,
    "canvasHeight": 240
  },
  "renderOptions": {
    "flattenToPdf": true,
    "keepSignatureImage": true,
    "imageScaleMode": "FIT"
  }
}
```

Response DTO:
```json
{
  "signingDocumentId": 9001,
  "status": "Completed",
  "signedFile": {
    "fileId": 701,
    "fileName": "contract_signed_20260324.pdf",
    "fileType": "PDF",
    "url": "https://...",
    "size": 256,
    "checksum": "..."
  },
  "signatureImageFile": {
    "fileId": 700,
    "fileName": "signature.png",
    "fileType": "PNG",
    "url": "https://...",
    "size": 12,
    "checksum": "..."
  },
  "signedAt": "2026-03-24T10:15:32+08:00",
  "evidenceId": "abc123"
}
```

### Signing Rules
The current `sign` flow completes the following steps in one request:
1. Validate current user, recipient, signing request status, and expiration time.
2. Upload the signature image file and persist `signedImageId`.
3. Build the original PDF from `DocumentTemplate`.
4. Resolve signature placement:
   - Prefer `signSlotCode` by locating a PDF form field in the source PDF.
   - Fallback to `placement` if the slot does not exist or the source PDF has no matching field.
5. Stamp the signature image onto the PDF.
6. Upload the signed PDF and persist `signedPdfId`.
7. Persist `signatureEvidence`, `evidenceId`, signer info, and sign timestamp.
8. Update `SigningDocument.status` and refresh `SigningRequest.status`.

### DocumentTemplateSignSlot
`DocumentTemplateSignSlot` stores predefined sign slot configurations for a document template.
Each slot defines a named region where a signature can be placed.

| Field | Type | Description |
| --- | --- | --- |
| `templateId` | Long | Parent DocumentTemplate ID |
| `slotName` | String | Display name for the slot |
| `slotCode` | String | Code used in `signSlotCode` during signing |
| `sequence` | Integer | Order of the slot |
| `placement` | JsonNode | Default placement coordinates (page, x, y, width, height, unit) |
| `description` | String | Description text |

CRUD is available via `POST/GET/PUT/DELETE /DocumentTemplateSignSlot`.

### Placement Resolution
- `signSlotCode` is the recommended mode for template-defined sign slots. The system first tries to locate a matching PDF form field in the source PDF; if not found, it looks up the `DocumentTemplateSignSlot` by code.
- `placement` is the fallback for free positioning.
- Supported placement units:
  - `PT`
  - `PX`
  - `MM`
  - `CM`
  - `IN`

### Current Limitation
The current signing implementation builds the original PDF only from `DocumentTemplate`:
- `DocumentTemplate.fileId` with `PDF` is used directly
- `DocumentTemplate.fileId` with `DOCX` is rendered with empty data and then converted to PDF
- `DocumentTemplate.htmlTemplate` is rendered with empty data and converted to PDF

This means the current implementation is suitable for:
- signing fixed PDF templates
- signing static DOCX or HTML templates without business-row rendering

It does not yet support:
- signing a document that must first be rendered with business row data and then assigned to a `SigningDocument`
