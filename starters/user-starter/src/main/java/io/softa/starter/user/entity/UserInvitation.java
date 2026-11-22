package io.softa.starter.user.entity;

import java.io.Serial;
import java.time.LocalDateTime;

import io.softa.starter.user.enums.InvitationPurpose;
import io.softa.starter.user.enums.InvitationStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.framework.orm.enums.WidgetType;

/**
 * A password-set-by-email token record ("邀请明细") — issued when Ops invites a user (first-time
 * password set for an INVITED account) or when a user requests a forgot-password reset. One row per
 * issuance; a resend REVOKEs the prior PENDING row and issues a new one.
 *
 * <p>Lives in its OWN package ({@code io.softa.starter.user.invitation}) so the metadata scanner can
 * reconcile just this new model via a narrow {@code scanner-scope} — annotation-scanning the shared
 * {@code io.softa.starter.user.entity} package would clobber studio-defined fields there (e.g.
 * {@code UserAccount.roles}).
 *
 * <p><b>Multi-tenant</b>: each row carries a {@code tenant_id}, stamped &amp; filtered by the framework
 * so the authed 明细 view is naturally tenant-scoped. Issuance pins the context to the invited account's
 * tenant ({@code UserInvitationServiceImpl#issue}) — the self-service forgot-password path is public and
 * has no ambient tenant, so the context is set there explicitly. The public accept / inspect token
 * endpoints run under {@link io.softa.framework.orm.annotation.CrossTenant} (no tenant context) and match
 * the row by {@link #tokenHash} globally — the token hash is the unique key ({@code uk_user_invitation_token}).
 *
 * <p>Security: the raw token is emailed to the user and NEVER stored — only its SHA-256 hash lives
 * here (a DB leak can't yield usable tokens). The token is single-use (see {@link InvitationStatus})
 * and expires at {@link #expiresAt}.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(idStrategy = IdStrategy.DISTRIBUTED_LONG, multiTenant = true,
        description = "User invitation / password-set token record")
@Index(indexName = "uk_user_invitation_token", fields = {"tokenHash"}, unique = true)
public class UserInvitation extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Tenant ID")
    private Long tenantId;

    @Field(fieldType = FieldType.MANY_TO_ONE, relatedModel = UserAccount.class, required = true,
            description = "The account being invited / reset")
    private Long userId;

    @Field(length = 256, description = "Recipient email (denormalized for display + auditing); null when the "
            + "account had no work email and the invitation went out by SMS alone")
    private String email;

    @Field(widgetType = WidgetType.PHONE, description = "Recipient mobile, dial code included (denormalized for display + auditing); "
            + "null when the account had no work mobile. Together with email this records WHICH "
            + "channels one invitation was addressed to — the delivery OUTCOME lives in "
            + "message-starter's MailSendRecord / SmsSendRecord, which stay the authority on it")
    private String mobile;

    @Field(description = "Why the token was issued: INVITE (onboarding) / PASSWORD_RESET "
            + "(self-service) / REINVITE (the membership was bound to the wrong person)")
    private InvitationPurpose purpose;

    @Field(length = 500,
            description = "Why the membership was unbound, required for REINVITE and null "
                    + "otherwise. Kept on the invitation rather than only in the audit log because "
                    + "unbinding detaches a person from a membership that keeps its roles: the row "
                    + "that caused it has to carry the account of why, where anyone reviewing the "
                    + "membership will actually see it")
    private String reason;

    @Field(length = 64, copyable = false,
            description = "SHA-256 hex of the emailed token (the raw token is never stored)")
    private String tokenHash;

    @Field(description = "Lifecycle: PENDING / ACCEPTED / EXPIRED / REVOKED")
    private InvitationStatus status;

    @Field(description = "Who issued it (inviter userId; null for self-service reset)")
    private Long invitedBy;

    @Field(copyable = false, description = "When the invitation email was sent")
    private LocalDateTime sentAt;

    @Field(copyable = false, description = "Token expiry — after this the token is rejected")
    private LocalDateTime expiresAt;

    @Field(copyable = false, description = "When the token was accepted (password set)")
    private LocalDateTime acceptedAt;
}
