# Configuration
## MQ Topic:
```yml
mq:
  topics:
    change-log:
      topic: 
```

## Multi-Datasource
Application Scenarios:
* Read-write separation
* Operate multiple databases in one project

### Multiple Datasource Configuration
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

### Specify the datasource in Java code
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

### Deal with the problem of read-after-write consistency
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

#### 1.2 Primary Keys and Fields
- `sliceId`: physical primary key of a timeline model, used to update a slice.
- `effectiveStartDate`: effective start date of the timeline data.
- `effectiveEndDate`: effective end date of the timeline data.
- `id`: logical (business) primary key, compatible with non-timeline models. All business foreign keys referencing a timeline model use this field.
- If your database needs an auto-increment record number (such as `record_id`) for change logs, you can add it yourself. It is not a framework-reserved field.
- Recommended unique constraint: `(id, effectiveStartDate, effectiveEndDate)`.

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

#### 2.3 create APIs
- For `createOne/createList`, if `effectiveStartDate` is empty, it uses the current `effectiveDate`; if `effectiveEndDate` is empty, it is set to `9999-12-31`.
- If an existing `id` is provided, the system automatically splits or adjusts adjacent slices based on the new `effectiveStartDate`.

#### 2.4 update APIs
- The current implementation uses `sliceId` as the update primary key. Updating `effectiveStartDate` automatically corrects adjacent slices' `effectiveEndDate`.
- Manual updates to `effectiveEndDate` are not recommended. To create a new slice, use `create` with an existing `id` and a new `effectiveStartDate`.
- If an upper layer provides a "correct"-style API (update data without creating a new slice), it should locate by `sliceId` (the ORM currently does not provide a dedicated correct API).

#### 2.5 delete APIs
- `deleteById/deleteByIds`: deletes all slices for a business `id`.
- `deleteBySliceId`: deletes a single slice and automatically corrects adjacent slice ranges.

#### 2.6 search Join Rules for Timeline Associations
- When the related object is a timeline model, Many2One/One2One queries automatically append to the `LEFT JOIN ON` clause:
  `effectiveStartDate <= effectiveDate AND effectiveEndDate >= effectiveDate`.
- One2Many/Many2Many cascades also filter slices based on `effectiveDate`.

### Examples

#### 1) Model Definition
```java
@Data
@Schema(name = "ProductPrice")
@EqualsAndHashCode(callSuper = true)
public class ProductPrice extends TimelineModel {
    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "Business ID")
    private Long id;

    @Schema(description = "Product ID")
    private Long productId;

    @Schema(description = "Price")
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
