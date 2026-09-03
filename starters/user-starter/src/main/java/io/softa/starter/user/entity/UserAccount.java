package io.softa.starter.user.entity;

import java.io.Serial;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.starter.user.enums.AccountStatus;

/**
 * UserAccount Model
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(
        idStrategy = IdStrategy.DISTRIBUTED_LONG,
        multiTenant = true,
        searchName = {"nickname", "username"}
)
/**
 * A MEMBERSHIP: one person's employment at one company. The person is {@link UserProfile}.
 *
 * <p>Email stays GLOBALLY unique here, exactly as before. Relaxing it to per-tenant is what
 * enables one person to work for two tenants, and that belongs to the release which adds that
 * ability — not to this one, which only moves where credentials are stored. Login still resolves an
 * account by its email, and a relaxed index would make that resolution ambiguous.
 */
// Unique WITHIN a tenant, not globally (A3). The work email is this company's contact for an
// employment, and two unrelated customers employing people who happen to share an address is not a
// conflict — a global index made the second company's hire fail on the first company's data, and
// told them an account exists elsewhere while doing it.
//
// What made the global index load-bearing was login resolving an ACCOUNT by its email. It does not
// any more: it resolves a PERSON by UserIdentity.loginEmail, which carries its own unique index.
@Index(indexName = "uk_user_account_tenant_email", fields = {"tenantId", "email"}, unique = true,
        message = "This email is already registered in this company.")
@Index(indexName = "uk_user_account_tenant_profile", fields = {"tenantId", "profileId"},
        unique = true, message = "This person already has an account in this company.")
public class UserAccount extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Tenant ID")
    private Long tenantId;

    @Field(fieldType = FieldType.MANY_TO_ONE, relatedModel = UserProfile.class,
            description = "The person this membership belongs to. No delete cascade in either "
                    + "direction: closing one company's account must not remove the person, and "
                    + "deleting a person is not something a tenant-scoped action may do")
    private Long profileId;

    @Field(description = "Whether this membership is a consultant's rather than an employment. "
            + "Denormalised from ConsultantAuthorization on purpose: the tenant's account list has "
            + "to hide these rows on every read, and a join back to the platform's consultant "
            + "records on each of those reads would be both slower and easy to forget in a new "
            + "query. It also survives what the grant does not — a revoked grant is deleted while "
            + "the account is kept, and the tenant's audit log still has to name its actor as a "
            + "consultant. Set when the account is minted; never edited by a tenant")
    private Boolean consultant;

    @Field
    private String nickname;

    @Field
    private String username;

    // password and passwordSalt moved to UserProfile — a credential belongs to the person, not to
    // one employment. Both columns stay in the database but are no longer declared here: the
    // scanner never auto-DROPs, so rolling the binary back still finds its credentials. Dropping
    // them for real is a separate release step (user-02-drop-old-credential-columns.sql).

    @Field
    private String email;

    @Field
    private String mobile;

    @Field(copyable = false)
    private LocalDateTime activationTime;

    @Field(label = "Policy ID")
    private Long policyId;

    // DERIVED, not stored: the lock lives on the PERSON's credential
    // (UserIdentity.passwordLockedUntil) and this membership only reports it, so there is nothing
    // here for a write to own — two copies of one fact would drift the moment the lockout expires.
    // dynamic = true is what keeps it out of the DDL (SysDdlContextBuilder.isStored) while still
    // producing a sys_field row, so the account list gets the field in model metadata and can badge
    // it. UserAccountController fills it per row; see there for the batch read.
    // readonly: the field is derived on READ, so there is nothing here a write may own. This is
    // what keeps it out of getModelUpdatableFields — the set an update payload is intersected with
    // — and so out of POST /UserAccount/updateOne {"locked":true} rendering
    // UPDATE user_account SET locked = ?, which writes to the orphaned legacy column on an upgraded
    // database and fails with unknown-column on a fresh one. The value would be ignored either way
    // (stampPasswordLock overwrites it on every read), which is why accepting the write is worse
    // than refusing it: it silently does nothing visible. Read from sys_field, so it shares the
    // reconciliation window that UserAccountController's unconditional strip covers.
    @Field(label = "Password lock", dynamic = true, readonly = true)
    private Boolean locked;

    @Field
    private AccountStatus status;
    
    @Field(label = "Roles", fieldType = FieldType.MANY_TO_MANY,
            relatedModel = Role.class, joinModel = UserRoleRel.class,
            joinLeft = "userId", joinRight = "roleId")
    private List<Long> roles;
}
