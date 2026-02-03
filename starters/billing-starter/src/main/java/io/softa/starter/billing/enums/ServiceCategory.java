package io.softa.starter.billing.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Service category:
 * BirthChart, NamingService, FengShui, DateSelection
 * MarriageCompatibility, CareerFortune, AcademicFortune, WealthAnalysis
 */
@Getter
@AllArgsConstructor
public enum ServiceCategory {
    BIRTH_CHART("BirthChart"),
    NAMING_SERVICE("NamingService"),
    FENG_SHUI("FengShui"),
    DATE_SELECTION("DateSelection"),
    MARRIAGE_COMPATIBILITY("MarriageCompatibility"),
    CAREER_FORTUNE("CareerFortune"),
    ACADEMIC_FORTUNE("AcademicFortune"),
    WEALTH_ANALYSIS("WealthAnalysis"),
    ;

    @JsonValue
    private final String category;
}
