package io.softa.starter.metadata.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;

/**
 * SysPreData Model
 * The seed ledger: one binding row per seeded business row, mapping (model, preId) to the row id the
 * load created. A binding is scoped by tenantId, and the scope follows the SEEDED MODEL's tenancy
 * rather than the file that carried it: a shared model binds once at system scope (null), a
 * multiTenant model binds per tenant.
 * <p>
 * The ledger itself is deliberately not multiTenant. Not for storage — a multiTenant model is the
 * same single table with a tenant_id column — but to stay out of the ORM's unconditional
 * {@code tenant_id = context} read narrowing, which the ledger cannot live under: it is read across
 * scopes (a tenant load resolving a shared model's binding) and addressed for another tenant during
 * provisioning. Scope is an explicit per-lookup predicate in
 * {@code SysPreDataServiceImpl#getScopedBindings}, never ambient.
 * <p>
 * Unpaid cost of that exemption: the generic {@code /SysPreData} endpoints carry no tenant predicate,
 * so treat them as operator-only until the ledger gets a scope filter of its own.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(label = "System Predefined Data")
@Index(fields = {"model", "tenantId", "preId"}, unique = true)
public class SysPreData extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field
    private String model;

    @Field(label = "Pre ID", required = true, length = 128)
    private String preId;

    @Field(label = "Row ID", length = 128)
    private String rowId;

    @Field
    private Boolean frozen;

    @Field(label = "Tenant ID")
    private Long tenantId;

    @Field(label = "Source File", length = 128,
            description = "Seed file the row was last loaded from; tells what a file brought to a scope before")
    private String sourceFile;
}
