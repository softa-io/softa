package io.softa.framework.orm.constant;

import java.time.LocalDate;
import java.util.Set;
import com.google.common.collect.ImmutableSet;

/**
 * Model constant
 */
public interface ModelConstant {

    /** Model attribute constants */
    String ID = "id";
    // External ID, used for external system data synchronization, excluded from copy
    String EXTERNAL_ID = "externalId";
    String UUID = "uuid";
    String CODE = "code";
    // Reversed field: When performing a soft deletion, set deleted=true, active=false
    String SOFT_DELETED_FIELD = "deleted";
    String ACTIVE_CONTROL_FIELD = "active";
    // Reserved field: The display name composed of multiple fields, suitable for displaying the value of ManyToOne, OneToOne, OneToMany, ManyToMany.
    String DISPLAY_NAME = "displayName";
    // Reserved field: Multiple fields composed of OR search conditions, suitable for multiple fields of ManyToOne, OneToOne, etc. single search box for multi-field based search.
    String SEARCH_NAME = "searchName";
    // Reserved field: Tenant ID identifier
    String TENANT_ID = "tenantId";
    String TENANT_ID_COLUMN = "tenant_id";

    /**
     * The reference-data master that a multi-country model partitions by. Named by
     * string rather than by class: {@code CountryRegion} lives in reference-data-starter,
     * which the ORM must not depend on.
     */
    String COUNTRY_REGION_MODEL = "CountryRegion";

    /**
     * The field a multi-country model partitions by. <b>Required</b>, asserted at init — the
     * narrowing filters on this name without resolving anything per model.
     *
     * <p>Fixing it is what separates the axis from an attribute. A model may reference a country for
     * other reasons — the country that issued a document, the countries a bank serves — and only the
     * field with this name says "these rows are the country's". Resolving by relation target could
     * not tell the two apart, and guessing would narrow every read on the wrong axis.
     */
    String COUNTRY_FIELD = "country";

    /**
     * The model naming the employing company. Hard-coded rather than configured, matching the way
     * {@code EmployeeContextEnricher} hard-codes its HR model names: an application either has this
     * dimension under this name or does not have it at all, and every mechanism keyed on it degrades
     * to a no-op when the model is absent. One constant so the multi-company narrowing and the
     * request enricher cannot drift apart on what "the company" is.
     *
     * <p>Renamed from {@code LegalEntity} (2026-08): the model describes which company rows belong
     * to, and "legal entity" now names a different concept — the entity a contract is signed under,
     * carried by ordinary business fields, not by this axis. Deployments carrying rows keyed on the
     * old name (notably {@code role_data_scope.model}) must migrate them BEFORE this binary boots:
     * an unmatched model name reads as "not configured", which resolves to unrestricted.
     */
    String COMPANY_MODEL = "Company";

    /**
     * The field a multi-company model belongs through. <b>Required</b>, asserted at init — same
     * contract as {@link #COUNTRY_FIELD}.
     *
     * <p>Fixing the name is what lets a model hold more than one reference to a company without
     * declaring which is which: {@code PayGroup.payingEntityId} names the entity that pays a group,
     * and does not make the group belong to it. A model with no company column of its own declares
     * this name as a {@code dynamic} cascaded field ({@code cascadedField = "deptId.companyId"}),
     * which takes no column and is joined at query time.
     *
     * <p>Renamed from {@code legalEntityId} (2026-08), together with {@link #COMPANY_MODEL}. A model
     * may keep a field named {@code legalEntityId} — it then names the signing/issuing entity, an
     * attribute, and takes no part in the company axis.
     */
    String COMPANY_FIELD = "companyId";

    /**
     * The organisation-tree model a role's department grant is written against. Hard-coded for the
     * reason {@link #COMPANY_MODEL} is: an application either has this dimension under this name or
     * does not have it, and the grant degrades to a no-op when the model is absent. Its rows store
     * their place in the tree as a materialized {@code idPath}, which is how a granted department
     * expands to its descendants.
     */
    String DEPARTMENT_MODEL = "Department";

    /**
     * The field a model belongs to a department through — the anchor the department grant bounds,
     * when it is a {@code MANY_TO_ONE} / {@code ONE_TO_ONE} onto {@link #DEPARTMENT_MODEL}. Fixed by
     * name for the reason {@link #COMPANY_FIELD} is: a model may reference departments for other
     * reasons, and only this name says "the row is that department's"; a reference that is
     * configuration rather than ownership is named otherwise.
     */
    String DEPARTMENT_FIELD = "departmentId";

    /**
     * The model whose rows are people. Hard-coded for the reason {@link #DEPARTMENT_MODEL} is: a record
     * belonging to an employee belongs to that employee's department, which is how the department grant
     * reaches a model with no department of its own.
     */
    String EMPLOYEE_MODEL = "Employee";

    /**
     * The field a model's rows belong to an employee through, when it is a {@code MANY_TO_ONE} /
     * {@code ONE_TO_ONE} onto {@link #EMPLOYEE_MODEL}. Fixed by name for the reason
     * {@link #DEPARTMENT_FIELD} is: a reference to an employee under another name (an approver, a
     * manager) says nothing about whose record the row is.
     */
    String EMPLOYEE_FIELD = "employeeId";
    // Reserved field: Version number identifier, used for optimistic lock control
    String VERSION = "version";
    // Starting value of the optimistic-lock version, materialized into the catalog
    // (sys_field/design_field.default_value) by both authoring lanes and stamped onto
    // every insert by DataCreatePipeline.
    Integer DEFAULT_VERSION = 0;

    // Model translation suffix
    String MODEL_TRANS_SUFFIX = "Trans";
    // The fields of the translation model, which stores the language code and the ID of the business data.
    String LANGUAGE_CODE_COLUMN = "language_code";
    String TRANS_ROW_ID_COLUMN = "row_id";

    /**
     * The order of a paged read that names none, on a model without a {@code defaultOrder}: newest first. A model that
     * reads better another way (master data by name or sequence) declares its own. Non-paged reads get no fallback; a
     * record's lines and tags (ONE_TO_MANY, MANY_TO_MANY) are given the order they were entered in by the relation
     * processors that read them: see {@code BaseProcessor.orderAsEntered}.
     */
    String DEFAULT_PAGED_ORDER = "id DESC";

    /** Audit fields, which are also reserved fields. */
    String CREATED_TIME = "createdTime";
    String CREATED_ID = "createdId";
    String CREATED_BY = "createdBy";
    String UPDATED_TIME = "updatedTime";
    String UPDATED_ID = "updatedId";
    String UPDATED_BY = "updatedBy";
    Set<String> AUDIT_FIELDS = Set.of(CREATED_TIME, CREATED_ID, CREATED_BY, UPDATED_TIME, UPDATED_ID, UPDATED_BY);

    // Virtual fields: Fields that are not stored in the database, but are generated by the system.
    String COUNT = "count";
    Set<String> VIRTUAL_FIELDS = Set.of(COUNT);

    /** Fields of timeline model */
    // Timeline model physical primary key field name, UPDATE timeline row basis.
    String SLICE_ID = "sliceId";
    String EFFECTIVE_START_DATE = "effectiveStartDate";
    String EFFECTIVE_END_DATE = "effectiveEndDate";
    Set<String> PRIMARY_KEYS = Set.of(ID, SLICE_ID);
    Set<String> TIMELINE_FIELDS = Set.of(ID, SLICE_ID, EFFECTIVE_START_DATE, EFFECTIVE_END_DATE);
    // Default effective end date: 9999-12-31
    LocalDate MAX_EFFECTIVE_END_DATE = LocalDate.of(9999, 12, 31);
    // Column names in database
    String SLICE_ID_COLUMN = "slice_id";
    String EFFECTIVE_START_DATE_COLUMN = "effective_start_date";
    String EFFECTIVE_END_DATE_COLUMN = "effective_end_date";

    /** Reserved field names, cannot be used as custom field names */
    Set<String> RESERVED_KEYWORD = ImmutableSet.<String>builder()
            .add(ID)
            .add(SOFT_DELETED_FIELD)
            .add(DISPLAY_NAME)
            .add(SEARCH_NAME)
            .add(TENANT_ID)
            .add(VERSION)
            .addAll(AUDIT_FIELDS)
            .addAll(VIRTUAL_FIELDS)
            .addAll(TIMELINE_FIELDS)
            .build();

    /** System model names */
    Set<String> SYSTEM_MODEL = ImmutableSet.<String>builder()
            .add("SysModel")
            .add("SysModelTrans")
            .add("SysField")
            .add("SysFieldTrans")
            .add("SysOptionSet")
            .add("SysOptionSetTrans")
            .add("SysOptionItem")
            .add("SysOptionItemTrans")
            .add("SysModelIndex")
            .build();
}
