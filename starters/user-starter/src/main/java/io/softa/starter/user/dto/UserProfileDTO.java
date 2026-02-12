package io.softa.starter.user.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import io.softa.framework.base.enums.Language;
import io.softa.framework.base.enums.Timezone;
import io.softa.starter.user.enums.Gender;

/**
 * User profile DTO
 * Focus on user personal information and detailed information
 */
@Data
@Schema(description = "User profile DTO")
public class UserProfileDTO {

    @Schema(description = "Full Name")
    private String fullName;

    @Schema(description = "Chinese Name")
    private String chineseName;

    @Schema(description = "Gender")
    private Gender gender;

    @Schema(description = "Birth Date")
    private LocalDate birthDate;

    @Schema(description = "Birth Time")
    private LocalTime birthTime;

    @Schema(description = "Birth City")
    private String birthCity;

    @Schema(description = "Photo ID")
    private Long photoId;

    @Schema(description = "Preferred Language")
    private Language language;

    @Schema(description = "Timezone")
    private Timezone timezone;

}