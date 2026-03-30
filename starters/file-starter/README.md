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
- `file/`: document file generators and PDF signing helpers (Word, PDF, signing)

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

### 1.1 Relation Lookup Import (Cascaded Import)
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
- Import status can be `PROCESSING`, `SUCCESS`, `FAILURE`, `PARTIAL_FAILURE`.

### 4. Custom Import Handler
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

### Placement Resolution
- `signSlotCode` is the recommended mode for template-defined sign slots.
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
- Signing
  - `POST /SigningDocument/sign`
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
