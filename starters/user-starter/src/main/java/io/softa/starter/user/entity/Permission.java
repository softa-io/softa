package io.softa.starter.user.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;
import tools.jackson.databind.JsonNode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;

/**
 * Permission entry — a single business capability (button / API action) under a navigation.
 * Default standard CRUD permissions (list / detail / create / update / ...) are
 * derived per navigation; custom actions declare their own permission rows with
 * explicit endpoints.
 *
 * <p><b>Metadata note:</b> {@code io.softa.starter.user.entity} is NOT in scanner-scope, so these
 * annotations are not reconciled at runtime — the authoritative metadata is the studio-managed
 * {@code sys_*}. Annotations mirror the live {@code sys_field} for documentation / future scanning.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(idStrategy = IdStrategy.EXTERNAL_ID, displayName = {"name"}, searchName = {"name"}, defaultOrder = {"id"})
@Index(indexName = "idx_permission_navigation", fields = {"navigationId"})
public class Permission extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID", length = 128, description = "Permission ID (e.g. core-hr.employee.employee.transfer)")
    private String id;

    @Field(label = "Navigation ID", fieldType = FieldType.MANY_TO_ONE, relatedModel = Navigation.class,
            description = "Navigation ID this permission belongs to (FK navigation.id)")
    private String navigationId;

    @Field(length = 128, description = "Display name shown in admin Wizard (e.g. 'Transfer Employee')")
    private String name;

    /**
     * Explicit endpoints, or null to let {@code EndpointIndex} derive them by convention.
     *
     * <p>Entries are matched against {@code HttpServletRequest.getServletPath()}, which Spring has
     * already stripped of {@code server.servlet.context-path} — so write the in-app path and leave
     * the context out. Repeating it (or omitting the leading {@code '/'}) is rejected at startup
     * rather than silently producing a permission that matches nothing.
     *
     * <p>A path may legitimately begin with {@code /api}: message-starter's {@code MailApiController}
     * and {@code SmsApiController} own the {@code /api/mail} and {@code /api/sms} namespaces
     * <i>inside</i> the context, so under {@code /api/hcm} the browser calls
     * {@code /api/hcm/api/mail/send} and the servletPath is {@code /api/mail/send}. The check keys
     * off the configured context path for exactly this reason.
     */
    @Field(description = "Explicit endpoint list for non-conventional URLs; null means EndpointIndex derives by "
            + "convention. Format: ['POST /<Model>/<action>', ...]. Write the in-app path — the app context is "
            + "already stripped before matching, so entries repeating it or missing the leading '/' are rejected "
            + "at startup.")
    private JsonNode endpoints;

    @Field(length = 256, description = "Optional description")
    private String description;
}
