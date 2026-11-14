package io.softa.starter.permission.entity;

import java.io.Serial;

import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.IdStrategy;

/**
 * The row scope every caller gets on a model, on top of whatever their role configures.
 *
 * <p>Without an entry, a model with no scope anchor that nothing the caller was granted points at
 * resolves to no rows. That is the right answer for business data somebody forgot to grant, and the
 * wrong one for a table whose rows have no owner at all — a template catalogue, a server
 * configuration. Those come back empty with nothing on screen saying why: the menu grant opens the
 * page, the row scope empties it, and no layer in between reports the disagreement.
 *
 * <p>Platform-level reference data, seeded from {@code data-system/ModelDefaultScope.Builtin.json}
 * and shared by every tenant — what it records is a fact about the model's shape ("these rows have
 * no owner"), which does not differ between tenants. It is deliberately NOT a column on
 * {@code SysModel}: that table is the scanner's projection of the annotations, so a hand-set value
 * there would be diffed away on the next boot.
 *
 * <p>Code-as-id: {@link #id} IS the model name, so a row reads as "ImportTemplate → ALL" and the
 * seed needs no surrogate key to stay stable across environments.
 *
 * <p><b>It is a floor, not a fallback.</b> A role's own rules for the model are OR-ed with it, so
 * a declaration only ever adds rows: a role reaching some import histories still sees the ones its
 * holder ran. On a model declaring {@code ALL} the union is always everything, so no rule
 * configured on that model can narrow it — declare {@code ALL} only where row-level restriction
 * has nothing to restrict.
 *
 * <p><b>{@code ALL} is not a statement that the table is harmless.</b> It says row scope is not
 * this table's defence — access is decided by the menu / endpoint grant instead — and it settles
 * <i>only</i> row scope: tenant isolation, per-country narrowing and field masking are untouched.
 *
 * <p><b>It applies to writes as well as reads.</b> The scope filter carries no access type, and an
 * update or delete resolves the rows it may touch through the same filter — so {@code ALL} lets
 * anyone holding write permission on the model change or delete every row, not merely see them.
 * Where that is too much, keep the read side open and guard the write in the model's own save gate.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(idStrategy = IdStrategy.EXTERNAL_ID, businessKey = {"id"},
        description = "Fallback row scope per model, for callers holding no rule of their own",
        defaultOrder = {"id"})
public class ModelDefaultScope extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "Model", length = 100,
            description = "Model name — the row's identity, e.g. ImportTemplate")
    private String id;

    @Field(required = true, length = 64,
            description = "ScopeType code to fall back on: ALL (rows have no owner inside the "
                    + "tenant) or CREATED_BY_SELF (each row belongs to whoever created it)")
    private String scopeType;

    @Field(length = 255,
            description = "Why this model has no owner — the argument, so a later reader can "
                    + "re-check it rather than trusting the row")
    private String description;
}
