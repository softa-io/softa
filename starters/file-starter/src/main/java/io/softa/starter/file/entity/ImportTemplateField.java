package io.softa.starter.file.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.OnDelete;

/**
 * ImportTemplateField Model
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(label = "Import Template Fields", multiTenant = true, defaultOrder = {"sequence"})
public class ImportTemplateField extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Tenant ID")
    private Long tenantId;

    // onDelete = CASCADE: field mappings are owned by the template — deleting the template removes them.
    @Field(label = "Import Template ID", fieldType = FieldType.MANY_TO_ONE, relatedModel = ImportTemplate.class,
            onDelete = OnDelete.CASCADE)
    private Long templateId;

    @Field(required = true)
    private String fieldName;

    @Field
    private String customHeader;

    @Field
    private Integer sequence;

    @Field
    private Boolean required;

    @Field(length = 128)
    private String defaultValue;

    @Field(length = 256)
    private String description;

    /**
     * Suppress the dropdown this column would otherwise be given.
     *
     * <p>Whether a column offers a list is a product decision — the specification says so per column
     * ("非下拉值，填写员工code"). The resolver infers it from metadata instead, and metadata cannot tell a
     * dictionary from a data table: a bank and an employee are both a many-to-one carrying a name. So
     * the columns that point at people and departments were offering the entire staff list, which is
     * both what the spec forbids there and a page of other people's data in a downloadable file.
     *
     * <p>Opt-out, not opt-in: a column that says nothing keeps inferring, or every dropdown in every
     * template would go dark at once. Import is unaffected — the cell is validated the same way
     * whether or not it was picked from a list.
     */
    @Field(label = "No Dropdown", description = "Do not offer a dropdown on this column")
    private Boolean noDropdown;
}
