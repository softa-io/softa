package io.softa.starter.user.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;
import tools.jackson.databind.JsonNode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.IdStrategy;

/**
 * Sensitive field set — a named bundle of fields on one model that require
 * explicit grant to view. Admin grants per (role, navigation) which set ids apply.
 * Naming convention: kebab-case with model prefix (e.g. 'employee-compensation').
 *
 * <p><b>Metadata note:</b> {@code io.softa.starter.user.entity} is NOT in scanner-scope; annotations
 * mirror the studio-managed live {@code sys_field} (not reconciled at runtime).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(idStrategy = IdStrategy.EXTERNAL_ID, displayName = {"name"}, searchName = {"name"})
@Index(indexName = "idx_sensitive_field_set_model", fields = {"model"})
public class SensitiveFieldSet extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID", length = 64, description = "Set ID (e.g. 'employee-compensation')")
    private String id;

    @Field(length = 64, description = "Bound model name (MetaModel.modelName, PascalCase). Set only applies to this model")
    private String model;

    @Field(length = 128, description = "Display name (e.g. 'Employee Compensation')")
    private String name;

    /**
     * The short noun a refusal names these fields by — "IPA" for a set named "IPA Details", so the
     * message reads "edit IPA fields". Optional; the name is used when absent.
     */
    @Field(length = 64, description = "Short noun used in permission messages (e.g. 'IPA' → 'IPA fields'); defaults to the name")
    private String label;

    @Field(description = "Field codes covered by this set. Must be actual fields on the bound model (validated at startup)")
    private JsonNode fieldCodes;

    /**
     * UI-only hint: extra MetaModel names whose Wizard nav rows should also
     * list this SFS as configurable (in addition to {@link #model}). Pure
     * UX aggregation — does NOT change the mask engine's authority. The
     * canonical {@code model} field still drives the response field mask,
     * the field write guard's rejection, and the
     * {@code SensitiveFieldSetCache} grouping.
     *
     * <p>Typical use: a SFS bound to {@code EmpBankAccount} can declare
     * {@code attachedTo: ["Employee"]} so admins can check it on the
     * Employee nav row in the role Wizard, without having to drill into a
     * separate EmpBankAccount nav. The mask engine still resolves the SFS
     * via {@code SensitiveFieldSetCache.modelOf(setId)} → "EmpBankAccount",
     * so OneToOne sub-objects under Employee responses ({@code Employee.
     * empBankAcc.*}) get correctly unmasked / masked.
     *
     * <p>JSON array of MetaModel names (PascalCase). Null / empty array →
     * SFS only appears under its own {@link #model} nav row in the Wizard.
     * Validator ⑪ checks every referenced MetaModel exists.
     */
    @Field(description =
            "Optional extra MetaModel names whose Wizard nav rows should "
                    + "also list this SFS. UI hint only — does not change "
                    + "mask authority (still controlled by `model`).")
    private JsonNode attachedTo;

    @Field(length = 256, description = "Optional description")
    private String description;
}
