package io.softa.starter.user.entity;

import java.io.Serial;
import java.time.LocalDate;
import java.time.LocalTime;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.base.enums.Language;
import io.softa.framework.base.enums.Timezone;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.starter.user.enums.Gender;

/**
 * UserProfile Model
 */
@Data
@Schema(name = "UserProfile")
@EqualsAndHashCode(callSuper = true)
public class UserProfile extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "Tenant ID")
    private Long tenantId;

    @Schema(description = "User ID")
    private Long userId;

    @Schema(description = "Full Name")
    private String fullName;

    @Schema(description = "Chinese Name")
    private String chineseName;

    @Schema(description = "Birth Date")
    private LocalDate birthDate;

    @Schema(description = "Birth Time")
    private LocalTime birthTime;

    @Schema(description = "Birth City")
    private String birthCity;

    @Schema(description = "Gender")
    private Gender gender;

    @Schema(description = "Profile Photo File ID")
    private Long photoId;

    @Schema(description = "Language")
    private Language language;

    @Schema(description = "Timezone")
    private Timezone timezone;
}