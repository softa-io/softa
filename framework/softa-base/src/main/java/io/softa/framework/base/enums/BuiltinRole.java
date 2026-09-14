package io.softa.framework.base.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * The role codes the platform itself defines, as opposed to the ones an administrator creates.
 *
 * <p>These are the values that carry meaning to the product wherever a user's {@code roleCodes} are
 * read — the gate, the row-scope filter, the field mask, the write guard, the navigation build, the
 * mail console. An administrator-created role has a null code and never appears here.
 *
 * <p>Here, in the framework's base, because four starters need to name them and no two of them may
 * depend on each other: {@code permission-starter} and {@code user-starter} are deliberately
 * decoupled, and {@code message-starter} knows neither. Each had grown its own copy of the same
 * three literals — nine in all, across {@code PermissionInfo}, {@code RoleConstant},
 * {@code DefaultPermissionSnapshotProvider} and {@code MailSendRecordController} — so a fifth
 * reserved code would have had to be added in four places, and a typo in any one of them fails
 * open: a role code that matches nothing simply grants nothing, silently.
 *
 * <p>This enum is the identity only. What each principal may DO is a policy of whatever is asking,
 * and stays there: the endpoint gate decides which of them the platform-only endpoints are barred
 * to, the snapshot builder decides which navigations each resolves to, and those two answers group
 * the same three differently — the gate treats a consultant separately, the snapshot groups them
 * with a tenant admin. A shared type carrying either answer would be a type carrying both.
 */
@Getter
@AllArgsConstructor
public enum BuiltinRole {

    /**
     * The platform super-admin. Holders short-circuit the data-plane checks entirely and keep the
     * platform's own screens; they are the one principal the platform-only endpoints exist for.
     */
    SUPER_ADMIN("Super Admin"),

    /**
     * A tenant's super-admin. Bypasses the permission gate within its own tenant, never across
     * tenants, and is refused the platform's own endpoints.
     */
    TENANT_ADMIN("Tenant Admin"),

    /**
     * Platform implementation staff authorized into a customer for a bounded period.
     *
     * <p>Unlike the two above, <b>no role row ever carries this code</b>: it is derived from the
     * membership while a permission snapshot or a UI context is built. A stored grant could not
     * survive what this principal has to survive — the entitlement cleanup hard-deletes role grants
     * on a plan downgrade and never restores them, and this role is not visible in the tenant's role
     * management, so nobody could put them back. Derived, the reach follows the plan on its own.
     */
    CONSULTANT("Consultant"),
    ;

    /** Display name. */
    private final String name;

    /**
     * The value stored in {@code role.code} and carried in a user's {@code roleCodes} set.
     *
     * <p>The constant's own name, because for these three the stored value already IS the constant's
     * name — every other enum here spells its code out because the two differ ({@code ACTIVE("Active")}),
     * and writing {@code SUPER_ADMIN("SUPER_ADMIN")} would only be the same string twice.
     *
     * <p>Which makes renaming a constant here a data migration: it renames the wire value with it,
     * and silently — a role code that matches nothing grants nothing rather than failing. What
     * catches that is {@code BuiltinRoleTest}, which compares against literals it does not share
     * with this file, so an IDE rename cannot carry the test along.
     */
    @JsonValue
    public String getCode() {
        return name();
    }
}
