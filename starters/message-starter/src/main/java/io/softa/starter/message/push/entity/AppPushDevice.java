package io.softa.starter.message.push.entity;

import java.io.Serial;
import java.time.LocalDateTime;

import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.IdStrategy;

/**
 * A device the APP registered for push notifications, bound to the person and the company account
 * signed in on it. One token belongs to one account at a time: signing in as someone else, or
 * switching company, moves it.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(label = "App Push Device",
        idStrategy = IdStrategy.DISTRIBUTED_LONG,
        multiTenant = true,
        copyable = false,
        description = "A device registered for APP push notifications, bound to the account signed in on it.")
@Index(indexName = "uk_app_push_device_token", fields = {"deviceToken"}, unique = true)
@Index(fields = {"accountId"})
@Index(fields = {"profileId"})
@Index(fields = {"sessionKey"})
public class AppPushDevice extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Tenant ID")
    private Long tenantId;

    /** APNs device token (iOS) or FCM registration token (Android). */
    @Field(required = true, length = 512)
    private String deviceToken;

    /** {@code ios} or {@code android}: which push service delivers to it. */
    @Field(required = true, length = 16)
    private String platform;

    @Field(length = 32)
    private String appVersion;

    @Field(length = 128)
    private String deviceModel;

    @Field(length = 32)
    private String locale;

    @Field(label = "Profile ID")
    private Long profileId;

    @Field(label = "Account ID")
    private Long accountId;

    /**
     * Digest of the session the device registered under, so the registration ends with that session.
     * The session id itself is a bearer credential and is never stored.
     */
    @Field(length = 64)
    private String sessionKey;

    /**
     * When the session it registered under was last known alive: set on registration and on each
     * renewal of that session. A session that ends on its own announces nothing, so a row not seen
     * for a session's lifetime belongs to one that has ended — senders skip it and a nightly job
     * removes it.
     */
    @Field
    private LocalDateTime lastSeenAt;
}
