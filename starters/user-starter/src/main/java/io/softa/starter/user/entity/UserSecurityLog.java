package io.softa.starter.user.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.starter.user.enums.SecurityAction;
import io.softa.starter.user.enums.SecurityLogSource;

/**
 * Security log: changes to who an account belongs to, how its person signs in, and its status.
 *
 * <p>Append-only evidence. Numbers are stored masked; status changes store status names. A change
 * that touches the person rather than one company (a dormant number released, a password reset)
 * carries no tenant.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(
        idStrategy = IdStrategy.DISTRIBUTED_LONG,
        copyable = false,
        multiTenant = true
)
@Index(fields = {"profileId", "createdTime"})
@Index(fields = {"accountId", "createdTime"})
public class UserSecurityLog extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Tenant ID")
    private Long tenantId;

    @Field(label = "Account ID")
    private Long accountId;

    @Field(label = "Profile ID")
    private Long profileId;

    @Field(required = true)
    private SecurityAction action;

    @Field(label = "Operator ID", description = "Who made the change; empty when the system did")
    private Long operatorId;

    @Field(length = 500, description = "Required for Reset User, unbind-and-reinvite and freezing")
    private String reason;

    @Field(length = 255, description = "Masked for numbers and addresses; the status name for status changes")
    private String oldValue;

    @Field(length = 255, description = "Masked for numbers and addresses; the status name for status changes")
    private String newValue;

    // Source and device are what the client said about itself (request headers), not proven: an
    // APP / WEB here is a claim, and the device model is whatever the client sent.
    @Field
    private SecurityLogSource source;

    @Field(length = 128, description = "Device model and platform when the change was made in the APP")
    private String device;

    @Field(description = "Where reminders went, e.g. sms,email")
    private String reminderChannels;

    @Field(length = 255, description = "Whether each reminder was handed off for delivery")
    private String reminderResult;
}
