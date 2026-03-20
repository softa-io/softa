# File Starter

File Starter provides three core capabilities for developers:
- Data import
- Data export
- Document export (Word/PDF)

This document focuses on developer usage and API-level examples.

## Code Structure

- `excel/export/strategy`: export strategy selection and concrete export implementations
- `excel/export/support`: shared export support components such as data fetch, template resolve, writer, upload, and custom export hooks
- `excel/imports`: import pipeline, handler factory, failure collection, persistence, and custom import hook
- `excel/style`: shared Excel style handlers
- `file/`: document file generators (Word, PDF)

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
- Database contains file metadata tables and file-starter tables:
  ImportTemplate, ImportTemplateField, ImportHistory,
  ExportTemplate, ExportTemplateField, ExportHistory,
  DocumentTemplate.

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
- Import by configured template (ImportTemplate + ImportTemplateField)
- Dynamic mapping import (no template, mapping provided in request)

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

### A1. Import By Template (Configured)
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

### A1.1 Relation Lookup Import (Cascaded Import)
The `fieldName` in ImportTemplateField (or `importFieldDTOList` in dynamic import) supports **dotted-path relation lookup** via `RelationLookupResolver`. Instead of importing a raw FK id, you can import a human-readable business key of the related model, and the system will reverse-lookup the FK id automatically.

**Syntax:** `{fkField}.{businessKey}` — e.g. `deptId.code`, `deptId.name`

**How it works:**
1. The system detects dotted-path fields whose root is a ManyToOne/OneToOne field.
2. Groups them by root FK field (e.g. `deptId.code` and `deptId.name` form one group).
3. Batch-queries the related model by the business key values to resolve FK ids.
4. Writes back the resolved FK id to the root field (`deptId`) and removes the dotted-path columns.

**Rules:**
- Only **single-level** cascade is supported: `deptId.code` ✅, `deptId.companyId.code` ❌
- A direct FK field (e.g. `deptId`) and a lookup field (e.g. `deptId.code`) **must not coexist** in the same template.
- Multiple lookup fields sharing the same root are combined as a composite business key (e.g. `deptId.code` + `deptId.name` together uniquely identify a Department).
- When all lookup values in a row are empty:
  - If `ignoreEmpty = true`: the FK field is skipped (not written).
  - If `ignoreEmpty = false`: the FK field is explicitly set to `null`.
- When a lookup fails (no matching record found):
  - If `skipException = true`: the row is marked as failed with a reason message.
  - If `skipException = false`: a `ValidationException` is thrown immediately.

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

### A2. Dynamic Mapping Import (No Template)
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

### A3. Import Result and Failed Rows
- Import returns `ImportHistory`.
- If any row fails, a “failed data” Excel file is generated and saved, with a `Failed Reason` column.
- Import status can be `PROCESSING`, `SUCCESS`, `FAILURE`, `PARTIAL_FAILURE`.

### A4. Custom Import Handler
You can register a Spring bean implementing `CustomImportHandler` and reference it by name in
`ImportTemplate.customHandler` or `ImportWizard.customHandler`.

```java
import io.softa.starter.file.excel.imports.CustomImportHandler;

@Component("productImportHandler")
public class ProductImportHandler implements CustomImportHandler {
    @Override
    public void handleImportData(List<Map<String, Object>> rows, Map<String, Object> env) {
        // custom preprocessing
    }
}
```

Contract:
- You may update row values in place.
- You may mark a row failed by writing `FileConstant.FAILED_REASON`.
- Do not add, remove, reorder, or replace row objects.

## B. Data Export
File Starter supports three export modes:
- Export by template fields
- Export by file template
- Dynamic export

### ExportTemplate Configuration Table
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
- `ConvertType.DISPLAY` is used, so option fields show `itemName` and relation fields show `displayName`.

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

### B1. Export By Template Fields
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

### B2. Export By File Template (Upload Template File)
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

### B3. Dynamic Export
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

### B4. Custom Export Handler
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
  4. Convert {{ }} -> ${} and render HTML via FreeMarker (PdfFileGenerator)
  5. Convert HTML to PDF via OpenPDF
  6. Upload to OSS -> return FileInfo
```

### WORD Template Syntax
- Uses `{{ variable }}` placeholder syntax with Spring EL support.
- Use `{{#fieldName}}` for OneToMany fields rendered as looping table rows via `LoopRowTableRenderPolicy`.
- OneToMany fields are auto-detected from model metadata; SubQueries are built automatically to load related data.

### RICH_TEXT Template
- `htmlTemplate` stores HTML with `{{ variable }}` placeholders.
- Placeholders are converted to FreeMarker `${}` syntax before rendering.
- The rendered HTML is converted to PDF via OpenPDF.

### Endpoint
- `GET /DocumentTemplate/generateDocument?templateId={id}&rowId={rowId}`

Example:
```bash
curl -X GET 'http://localhost:8080/DocumentTemplate/generateDocument?templateId=3001&rowId=10001'
```

### Programmatic API
Besides the REST endpoint (which fetches data by `modelName` + `rowId`), you can also call `DocumentTemplateService` directly with a custom data object:

```java
@Autowired
private DocumentTemplateService documentTemplateService;

// Option 1: Generate by rowId (fetches data from the model automatically)
FileInfo fileInfo = documentTemplateService.generateDocument(templateId, rowId);

// Option 2: Generate by custom data object (Map or POJO)
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

The `generateDocument(templateId, data)` overload skips the model data fetch step and renders the template directly with the provided data. This is useful when:
- The data comes from an external source or custom aggregation.
- You want to render a document from a non-model data structure.

## REST APIs (Summary)
- Import
  - `POST /import/importByTemplate`
  - `POST /import/dynamicImport`
  - `GET /ImportTemplate/getTemplateFile`
- Export
  - `POST /export/exportByTemplate` (dispatches to field-template or file-template mode based on `customFileTemplate`)
  - `POST /export/dynamicExport`
- Document
  - `GET /DocumentTemplate/generateDocument`
- Template Listing
  - `POST /ImportTemplate/listByModel`
  - `POST /ExportTemplate/listByModel`

## Examples
Export params (with cascaded fields):
```json
{
  "fields": ["id", "name", "code", "status", "deptId.name", "deptId.managerId.name"],
  "filters": ["status", "=", "ACTIVE"],
  "orders": ["createdTime", "DESC"],
  "limit": 200,
  "groupBy": [],
  "effectiveDate": "2026-03-03"
}
```

Import field mapping (with relation lookup):
```json
[
  {"header": "Product Code", "fieldName": "productCode", "required": true},
  {"header": "Product Name", "fieldName": "productName", "required": true},
  {"header": "Category Code", "fieldName": "categoryId.code", "required": true},
  {"header": "Price", "fieldName": "price"}
]
```

Import field mapping (direct FK id):
```json
[
  {"header": "Product Code", "fieldName": "productCode", "required": true},
  {"header": "Product Name", "fieldName": "productName", "required": true},
  {"header": "Price", "fieldName": "price"}
]
```

Import env:
```json
{
  "deptId": 10,
  "source": "manual"
}
```
