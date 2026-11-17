package io.softa.starter.user.dto;

import java.time.LocalDate;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * What the platform's Consultant Profile form saves.
 *
 * <p>One payload for create and edit, because the form is one form: basic details plus whatever
 * changed in the authorization table. The table arrives as changes rather than as a replacement
 * set — see {@code ConsultantService.applyAuthorizations} for why.
 *
 * <p><b>Why this is not an entity.</b> One save spans FOUR tables, and no entity is the shape of
 * it: the username belongs to {@link io.softa.starter.user.entity.UserProfile}, the email and
 * mobile to {@link io.softa.starter.user.entity.UserIdentity}, the Enabled switch to
 * {@link io.softa.starter.user.entity.ConsultantProfile}, and the grant rows to
 * {@link io.softa.starter.user.entity.ConsultantAuthorization}. Taking
 * {@code ConsultantProfile} as the parameter would carry exactly one of the five fields the
 * screen edits.
 *
 * <p>Nor can the generic model surface bridge it. A cascade write reaches a satellite whose FK
 * points AT the row being saved; {@code UserIdentity}'s FK points at the PROFILE, so there is no
 * forward path from the consultant record to the identifiers — which is deliberate, since those
 * identifiers are kept off the browsable person record on purpose.
 *
 * <p>And splitting it into four calls is not available either: the four writes have to land or fail
 * together. A grant saved without its minted membership, or a person created without the consultant
 * record, is a half-authorized consultant that the form cannot show and an operator cannot repair —
 * which is why {@code ConsultantService.save} is {@code @Transactional} over the whole payload.
 */
@Data
public class ConsultantProfileDTO {

    /**
     * The person, when this edits an existing one.
     *
     * <p>Also how a consultant is made out of somebody who ALREADY exists — an employee of some
     * client company being brought onto the implementation team. Login identifiers are globally
     * unique, so that person cannot be given a second profile with the same address; they are the
     * same person wearing another hat, which is exactly what the model represents. Null creates a
     * new person.
     */
    private Long profileId;

    /**
     * The person's full name, and its column's width. Checked here so an over-long name is refused by its
     * own name — the column's check answered with the model and column it lands in.
     */
    @NotBlank(message = "Username is required")
    @Size(max = 64, message = "Username must be 64 characters or fewer")
    private String username;

    /**
     * Required, like {@link #mobile}: they are the consultant's two login identifiers, and the
     * profile is defined with both.
     *
     * <p>Blank answers with the format message rather than a separate "required" one — an empty
     * address is not a valid address, and it is the sentence the form already shows for it.
     *
     * <p>This was once either-or, because the edit form showed the email read-only and a person who
     * had joined by mobile alone could not be saved at all. Both fields are editable there now, so
     * the operator can supply the missing one, and the relaxation only let a payload create a
     * consultant the form itself would have refused.
     */
    @NotBlank(message = "Please enter a valid email address")
    @Email(message = "Please enter a valid email address")
    @Size(max = 64, message = "Email must be 64 characters or fewer")
    private String email;

    @NotBlank(message = "Mobile is required")
    @Size(max = 64, message = "Mobile must be 64 characters or fewer")
    private String mobile;

    /** Defaults to enabled on create — a consultant is made in order to be used. */
    private Boolean active;

    /**
     * What the operator did to the Authorized Tenants table, as changes rather than as a new table.
     *
     * <p>Sending the table whole made every save assert the whole truth, including about rows the
     * operator never touched — so two people with the form open overwrote each other without either
     * of them editing the same thing. Stating only the changes makes "I did not touch this" a thing
     * the payload can express, and an operator who changed nothing then writes nothing.
     */
    @Valid
    private AuthorizationPatch authorizations;

    /**
     * Added, re-dated and revoked grants.
     *
     * <p>The same three operations the generic OneToMany patch takes, in the same shape, so a
     * screen saving a child table does it one way everywhere.
     */
    @Data
    public static class AuthorizationPatch {

        /** Grants the operator added; no id, since the row does not exist yet. */
        @Valid
        private List<AuthorizationRow> create;

        /**
         * Grants whose end date the operator moved, each naming the row by id.
         *
         * <p>Only the date: a grant's company is not editable. Moving access from one company to
         * another is revoking one grant and adding another, which is what it is — the membership
         * minted under the old company has to be closed either way.
         */
        @Valid
        private List<AuthorizationRow> update;

        /** Ids of grants the operator removed from the table. */
        private List<Long> delete;
    }

    /** One row of the Authorized Tenants table. */
    @Data
    public static class AuthorizationRow {

        /** The grant being re-dated; null on a row being added. */
        private Long id;

        @NotNull(message = "Every authorization needs a company")
        private Long tenantId;

        /**
         * Last day this grant admits, inclusive — <b>empty means open-ended</b>.
         *
         * <p>Optional, and there is no start date: a grant admits from the moment it is saved. See
         * {@code ConsultantAuthorization.endDate} for why both of those are the way they are.
         *
         * <p>On an {@code update} row this value is the new one whatever it is: empty means the
         * operator cleared the date and the grant is now open-ended, never "leave it alone". The
         * row carries one editable field, so there is nothing for absence to mean.
         */
        private LocalDate endDate;
    }
}
