package io.softa.starter.user.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.starter.user.enums.LoginChannel;
import io.softa.starter.user.enums.LoginDeviceType;
import io.softa.starter.user.enums.LoginEvent;
import io.softa.starter.user.enums.LoginMethod;
import io.softa.starter.user.enums.LoginStatus;

/**
 * UserLoginHistory Model
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(
        idStrategy = IdStrategy.DISTRIBUTED_LONG,
        copyable = false,
        // Who logged in from where is per-tenant data, and the audit page is reachable by a tenant
        // admin — unisolated it showed every tenant's login trail, IP and location included (#956).
        //
        // Written by authentication itself, at a point where the tenant IS known (the login has
        // resolved a user), so rows written from here on carry it. Rows written before this change
        // have tenantId NULL and stay invisible to tenants until the release backfill runs; the
        // platform still sees them, since SUPER_ADMIN reads cross-tenant.
        multiTenant = true
)
public class UserLoginHistory extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Tenant ID")
    private Long tenantId;

    @Field(label = "User ID", description = "The account; empty when no company was involved, such as "
            + "a failed sign-in")
    private Long userId;

    @Field(label = "Profile ID", description = "The person, when known")
    private Long profileId;

    @Field
    private LoginEvent event;

    @Field
    private LoginChannel channel;

    @Field
    private LoginMethod loginMethod;

    @Field
    private LoginDeviceType loginDeviceType;

    @Field(label = "IP Address")
    private String ipAddress;

    @Field(length = 255)
    private String userAgent;

    @Field
    private String location;

    @Field
    private LoginStatus status;

    @Field(length = 255)
    private String failureReason;

    @Field(description = "ios / android for the APP; empty for Web")
    private String platform;

    @Field(label = "App Version")
    private String appVersion;

    @Field
    private String deviceModel;

    @Field(label = "From Account ID", description = "On a tenant switch, the account switched away from")
    private Long fromAccountId;
}
