package io.softa.starter.file.entity;

import java.io.Serial;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.FieldType;
import io.softa.starter.file.enums.ImportRule;

/**
 * ImportTemplate Model
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(multiTenant = true)
public class ImportTemplate extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Tenant ID")
    private Long tenantId;

    @Field(label = "Template Name", required = true)
    private String name;

    @Field(required = true)
    private String modelName;

    @Field(required = true)
    private ImportRule importRule;

    @Field
    private List<String> uniqueConstraints;

    @Field(label = "Ignore Empty Value")
    private Boolean ignoreEmpty;

    @Field(label = "Skip Abnormal Data")
    private Boolean skipException;

    @Field(label = "Custom Import Handler", length = 128)
    private String customHandler;

    @Field(label = "Synchronous Import")
    private Boolean syncImport;

    /**
     * True when this template belongs only to its own model's page.
     *
     * <p>{@code listByModel(X)} offers X's templates plus those of X's child models, and "child
     * model" comes from {@code ModelManager.getChildModels}, which counts the target of every
     * OneToMany. A reverse reference is indistinguishable there from a composition: Employee
     * declares {@code managedDepartments} / {@code hrbpDepartments} to say "departments this person
     * leads", so Department became a child of Employee and the Department template turned up in the
     * employee import dialog (zingkey/zingkey-hcm#764).
     *
     * <p>Declared on the template rather than inferred from the model graph, because which page a
     * template belongs on is a property of the template — the relation graph only approximates it.
     * Defaults to false, so every existing template keeps today's behaviour and only the exceptions
     * are marked; a template that should have been marked and was not merely stays visible where it
     * is today, while the opposite default would make templates vanish from pages that need them.
     */
    @Field(label = "Standalone Template", defaultValue = "false",
            description = "Offered only on its own model's page, never through a parent's child models")
    private Boolean standalone;

    @Field(label = "Include Import Description")
    private Boolean includeDescription;

    /**
     * Country this template is written for, ISO 3166-1 alpha-2 (a to-one onto {@code CountryRegion}).
     *
     * <p><b>Null means every country</b>, and has to: the great majority of templates — job grades,
     * cost centres, departments — have no country dimension at all, and there is no value that would
     * be right for them. Null is also what every existing row holds the moment this column is added,
     * so a list that filtered it out would come back empty for everyone on the release that ships it.
     *
     * <p>Referenced by name rather than by class: {@code CountryRegion} lives in reference-data-starter
     * and file-starter does not depend on it. Same reason {@code Department.orgType} names
     * {@code TenantOptionItem} as a string.
     */
    @Field(label = "Country", fieldType = FieldType.MANY_TO_ONE, relatedModelName = "CountryRegion")
    private String country;

    @Field(length = 1000)
    private String description;

    @Field(fieldType = FieldType.ONE_TO_MANY, relatedField = "templateId")
    private List<ImportTemplateField> importFields;
}
