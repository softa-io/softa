# File Starter

File Starter provides three core capabilities for developers:
- Data import
- Data export
- Document export (Word/PDF)

This document focuses on developer usage and API-level examples.

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
| `fieldName` | String | `null` | Model field name |
| `customHeader` | String | `null` | Custom Excel header |
| `sequence` | Integer | `null` | Field order in template |
| `required` | Boolean | `null` | Required field |
| `defaultValue` | String | `null` | Default value (supports `#{var}`) |
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
- Default values in ImportTemplateField support variables `#{var}`. Variables are resolved from `env`.
- If `syncImport = true`, import is executed in-process.
- If `syncImport = false`, an async import message is sent to MQ.

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

This endpoint accepts a `multipart/form-data` payload with `ImportWizard` fields.

Key fields:
- `modelName`
- `file`
- `importRule`: `CreateOrUpdate` | `OnlyCreate` | `OnlyUpdate`
- `uniqueConstraints`: comma-separated field names
- `importFieldStr`: JSON string of header-to-field mappings
- `ignoreEmpty`, `skipException`, `customHandler`, `syncImport`

Example:
```bash
curl -X POST http://localhost:8080/import/dynamicImport \
  -F modelName=Product \
  -F importRule=CreateOrUpdate \
  -F uniqueConstraints=productCode \
  -F importFieldStr='[{"header":"Product Code","fieldName":"productCode","required":true},{"header":"Product Name","fieldName":"productName","required":true},{"header":"Price","fieldName":"price"}]' \
  -F syncImport=true \
  -F file=@/path/to/import.xlsx
```

### A3. Import Result and Failed Rows
- Import returns `ImportHistory`.
- If any row fails, a “failed data” Excel file is generated and saved, with a `Failed Reason` column.
- Import status can be `PROCESSING`, `SUCCESS`, `FAILURE`, `PARTIAL_FAILURE`.

### A4. Custom Import Handler
You can register a Spring bean implementing `CustomImportHandler` and reference it by name in
`ImportTemplate.customHandler` or `ImportWizard.customHandler`.

```java
@Component("productImportHandler")
public class ProductImportHandler implements CustomImportHandler {
    @Override
    public void handleImportData(List<Map<String, Object>> rows, Map<String, Object> env) {
        // custom preprocessing
    }
}
```

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
| `fileId` | Long | `null` | Template file id (for file-template export) |
| `filters` | Filters | `null` | Default filters |
| `orders` | Orders | `null` | Default orders |
| `customHandler` | String | `null` | Spring bean name for CustomExportHandler |
| `enableTranspose` | Boolean | `null` | Whether to transpose output (not implemented in starter) |

### ExportTemplateField Configuration Table
| Field | Type | Default | Description |
| --- | --- | --- | --- |
| `templateId` | Long | `null` | ExportTemplate id |
| `fieldName` | String | `null` | Model field name |
| `customHeader` | String | `null` | Custom column header |
| `sequence` | Integer | `null` | Field order in export |
| `ignored` | Boolean | `null` | Whether to ignore the field in output |

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
This mode uses an uploaded Excel template file with placeholders like `{field}` or `{object.field}`.
The system extracts variables from the template to decide which fields to query.

Endpoint:
- `POST /export/exportByFileTemplate?exportTemplateId={id}`

Example:
```bash
curl -X POST http://localhost:8080/export/exportByFileTemplate?exportTemplateId=2002 \
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
@Component("productExportHandler")
public class ProductExportHandler implements CustomExportHandler {
    @Override
    public void handleExportData(List<Map<String, Object>> rows) {
        // custom post-processing
    }
}
```

## C. Document Export (Word/PDF)
Document templates are stored in `DocumentTemplate` and rendered as Word or PDF.

### DocumentTemplate Configuration Table
| Field | Type | Default | Description |
| --- | --- | --- | --- |
| `modelName` | String | `null` | Model name to fetch data |
| `fileName` | String | `null` | Output file name |
| `fileId` | Long | `null` | Template file id (docx) |
| `convertToPdf` | Boolean | `null` | Convert to PDF if true |

DocumentTemplate key fields:
- `modelName`, `fileName`, `fileId`, `convertToPdf`

Template syntax:
- Uses `#{var}` placeholders, supports Spring EL.
- Supports table row loops via `LoopRowTableRenderPolicy`.

Endpoint:
- `GET /DocumentTemplate/generateDocument?templateId={id}&rowId={rowId}`

Example:
```bash
curl -X GET 'http://localhost:8080/DocumentTemplate/generateDocument?templateId=3001&rowId=10001'
```

If `convertToPdf=true`, the generated file is PDF; otherwise Word.

## REST APIs (Summary)
- Import
  - `POST /import/importByTemplate`
  - `POST /import/dynamicImport`
  - `GET /ImportTemplate/getTemplateFile`
- Export
  - `POST /export/exportByTemplate`
  - `POST /export/exportByFileTemplate`
  - `POST /export/dynamicExport`
- Document
  - `GET /DocumentTemplate/generateDocument`
- Template Listing
  - `POST /ImportTemplate/listByModel`
  - `POST /ExportTemplate/listByModel`

## Examples
Export params:
```json
{
  "fields": ["id", "name", "code", "status"],
  "filters": ["status", "=", "ACTIVE"],
  "orders": ["createdTime", "DESC"],
  "limit": 200,
  "groupBy": [],
  "effectiveDate": "2026-03-03"
}
```

Import field mapping:
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
