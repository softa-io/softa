package io.softa.starter.user.entity;

import java.io.Serial;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.framework.orm.enums.OnDelete;

/**
 * The PERSON's AUTHENTICATION — login identifiers and the password, split out from {@link UserProfile}.
 *
 * <p>Why its own model rather than columns on the profile: credentials and personal information are
 * different concerns with opposite exposure. A directory of people's names and photos is ordinary
 * browsable data; a password hash is not. Keeping them on one model meant any decision about exposing
 * one applied to the other as well. Separated, {@code UserProfile} carries a normal CRUD surface and
 * this model is reached by the credential paths in typed service code.
 *
 * <p>Served by the generic {@code ModelController} surface like any other registered model, gated by
 * role grants and the endpoint registry. A platform super-admin bypasses that gate, as it does
 * everywhere.
 *
 * <p>Global, exactly like the profile: a person has ONE set of credentials no matter how many
 * tenants employ them. One row per person, linked 1:1 to {@link UserProfile} via {@code profileId}
 * (the FK is here, not on the profile, so the credential path resolves an identity straight from an
 * account's {@code profileId} without loading the profile at all). {@code onDelete = CASCADE}: deleting
 * a person deletes their credentials with them, so a tenant purge that clears profiles leaves no
 * orphaned identity behind.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(
        idStrategy = IdStrategy.DISTRIBUTED_LONG
)
@Index(indexName = "uk_user_identity_profile", fields = {"profileId"}, unique = true)
@Index(indexName = "uk_user_identity_login_email", fields = {"loginEmail"}, unique = true,
        message = "This email is already a sign-in identifier for someone else.")
@Index(indexName = "uk_user_identity_login_mobile", fields = {"loginMobile"}, unique = true,
        message = "This mobile is already a sign-in identifier for someone else.")
// The login identifiers ARE unique, and have to be: login resolves a person by one of them, so two
// people holding the same value cannot be told apart — a code sent there says nothing about which
// of them is signing in, and resolution can only refuse. Enforced by the database rather than by
// application code, because the check and the write would otherwise race.
//
// NULLs are the point of leaving these nullable: MySQL allows many rows to be null in a unique
// index, and most people have only one channel. A shared work number simply does not become
// anyone's identifier — see UserIdentityService.isIdentifierClaimable, which is what keeps the
// seeding paths from manufacturing a collision out of an ordinary shared contact.
//
// Reusing a leaver's number is still possible: a login mobile whose person has no account left
// outside Deactivated is dormant, and is released before anyone else uses it
// (UserAccountService.releaseDormantLoginMobile); a company work mobile is released at off-boarding.
public class UserIdentity extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    /** The person these credentials belong to. FK on this side so an identity resolves from an
     * account's {@code profileId} in one query. Unique — one identity per person. */
    @Field(fieldType = FieldType.ONE_TO_ONE, relatedModel = UserProfile.class, onDelete = OnDelete.CASCADE)
    private Long profileId;

    @Field(description = "Login identifier. Seeded from the account's work email when a person is "
            + "created; identifies a human, not an employment")
    private String loginEmail;

    @Field(description = "Login identifier, dial-code format. Same reasoning as loginEmail")
    private String loginMobile;

    @Field(copyable = false, length = 256,
            description = "Password hash. length 256 because the stored hash is 128 characters, so "
                    + "the 64-char type default would silently truncate it and no credential could "
                    + "be written at all")
    private String password;

    @Field(copyable = false, description = "Per-row salt for the password hash")
    private String passwordSalt;

    @Field(copyable = false,
            description = "Reserved for the lockout feature: when set and in the future, PASSWORD "
                    + "login will be refused until then. NOT ENFORCED YET — no code writes or reads "
                    + "it in this release; the column exists so the lockout release ships without "
                    + "another schema change. On the person, so switching company will buy no extra "
                    + "attempts")
    private LocalDateTime passwordLockedUntil;

    @Field(label = "Last Account ID", copyable = false,
            description = "The account (company) this person entered last; the login entry rule "
                    + "enters it directly while it is still enterable")
    private Long lastAccountId;
}
