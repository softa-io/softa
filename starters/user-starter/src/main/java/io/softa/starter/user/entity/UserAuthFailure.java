package io.softa.starter.user.entity;

import java.io.Serial;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import tools.jackson.databind.JsonNode;

import io.softa.framework.orm.entity.AuditableModel;

/**
 * UserAuthFailure Model
 */
@Data
@Schema(name = "UserAuthFailure")
@EqualsAndHashCode(callSuper = true)
public class UserAuthFailure extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "User ID")
    private Long userId;

    @Schema(description = "Request Params")
    private JsonNode requestParams;

    @Schema(description = "Failure Reason")
    private String failureReason;

    @Schema(description = "Error Stack")
    private String errorStack;

    @Schema(description = "IP Address")
    private String ipAddress;

    @Schema(description = "User Agent")
    private String userAgent;

    @Schema(description = "Location")
    private String location;
}