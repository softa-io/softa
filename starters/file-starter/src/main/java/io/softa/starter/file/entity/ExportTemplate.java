package io.softa.starter.file.entity;

import java.io.Serial;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.FieldType;

/**
 * ExportTemplate Model
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(multiTenant = true, searchName = {"fileName"})
public class ExportTemplate extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Tenant ID")
    private Long tenantId;

    @Field(required = true)
    private String fileName;

    @Field
    private String sheetName;

    @Field(required = true)
    private String modelName;

    @Field
    private Boolean customFileTemplate;

    @Field(label = "File Template ID", fieldType = FieldType.FILE)
    private Long fileId;

    @Field(length = 256)
    private Filters filters;

    @Field
    private Orders orders;

    @Field(label = "Custom Export Handler", length = 128)
    private String customHandler;

    @Field
    private Boolean enableTranspose;

    @Field(fieldType = FieldType.ONE_TO_MANY, relatedField = "templateId")
    private List<ExportTemplateField> exportFields;

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
}
