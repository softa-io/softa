package io.softa.starter.referencedata.validation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.service.validation.WriteContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A company's time zone is one of its country's zones: filled with the country's default on create
 * when left empty, refused when it belongs to another country.
 */
class CompanyTimeZoneValidatorTest {

    private CompanyTimeZoneValidator validator;
    /** Whether the zone lookup ran outside the caller's row scope. */
    private final boolean[] unscoped = {false};

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        validator = new CompanyTimeZoneValidator();
        ModelService<Long> modelService = mock(ModelService.class);
        ReflectionTestUtils.setField(validator, "modelService", modelService);
        // NZ has two zones, Auckland the default; SG one; BV none.
        when(modelService.searchList(eq("CountryTimeZone"), any(FlexQuery.class))).thenAnswer(inv -> {
            unscoped[0] = ContextHolder.getContext().isSkipPermissionCheck();
            String filter = String.valueOf(((FlexQuery) inv.getArgument(1)).getFilters());
            if (filter.contains("NZ")) {
                return List.of(Map.of("id", "Pacific/Auckland", "defaultZone", true),
                        Map.of("id", "Pacific/Chatham", "defaultZone", false));
            }
            if (filter.contains("SG")) {
                return List.of(Map.of("id", "Asia/Singapore", "defaultZone", true));
            }
            return List.of();
        });
    }

    @Test
    void createWithoutZoneGetsTheCountryDefault() {
        Map<String, Object> row = new HashMap<>(Map.of("name", "Acme NZ", "country", "NZ"));
        WriteContext ctx = WriteContext.of("Company", AccessType.CREATE, row, null);

        validator.validateCreate(ctx);

        assertThat(ctx.hasErrors()).isFalse();
        assertThat(row).containsEntry("timeZone", "Pacific/Auckland");
        assertThat(unscoped[0]).isTrue();
    }

    @Test
    void createKeepsAZoneOfTheCountry() {
        Map<String, Object> row = new HashMap<>(Map.of("country", Map.of("id", "NZ"), "timeZone", "Pacific/Chatham"));
        WriteContext ctx = WriteContext.of("Company", AccessType.CREATE, row, null);

        validator.validateCreate(ctx);

        assertThat(ctx.hasErrors()).isFalse();
        assertThat(row).containsEntry("timeZone", "Pacific/Chatham");
    }

    @Test
    void createRefusesAZoneOfAnotherCountry() {
        Map<String, Object> row = new HashMap<>(Map.of("country", "SG", "timeZone", "Pacific/Auckland"));
        WriteContext ctx = WriteContext.of("Company", AccessType.CREATE, row, null);

        validator.validateCreate(ctx);

        assertThat(ctx.hasErrors()).isTrue();
    }

    @Test
    void saysNothingMoreWhenTheCountryItselfWasRefused() {
        Map<String, Object> row = new HashMap<>(Map.of("country", "Singapore", "timeZone", "Asia/Singapore"));
        WriteContext ctx = WriteContext.of("Company", AccessType.CREATE, row, null);
        ctx.reject("country", "\"Singapore\" is not a country code.");

        validator.validateCreate(ctx);

        assertThat(ctx.errors()).hasSize(1);
    }

    @Test
    void createInACountryWithoutZonesLeavesItEmpty() {
        Map<String, Object> row = new HashMap<>(Map.of("country", "BV"));
        WriteContext ctx = WriteContext.of("Company", AccessType.CREATE, row, null);

        validator.validateCreate(ctx);

        assertThat(ctx.hasErrors()).isFalse();
        assertThat(row).doesNotContainKey("timeZone");
    }

    @Test
    void changingTheCountryWithoutANewZoneIsRefused() {
        Map<String, Object> original = Map.of("id", 1L, "country", "SG", "timeZone", "Asia/Singapore");
        Map<String, Object> patch = new HashMap<>(Map.of("id", 1L, "country", "NZ"));
        Map<String, Object> merged = new HashMap<>(original);
        merged.putAll(patch);
        WriteContext ctx = WriteContext.of("Company", AccessType.UPDATE, merged, patch, original);

        validator.validateUpdate(ctx);

        assertThat(ctx.hasErrors()).isTrue();
    }

    @Test
    void changingTheCountryAndZoneTogetherPasses() {
        Map<String, Object> original = Map.of("id", 1L, "country", "SG", "timeZone", "Asia/Singapore");
        Map<String, Object> patch = new HashMap<>(Map.of("id", 1L, "country", "NZ", "timeZone", "Pacific/Auckland"));
        Map<String, Object> merged = new HashMap<>(original);
        merged.putAll(patch);
        WriteContext ctx = WriteContext.of("Company", AccessType.UPDATE, merged, patch, original);

        validator.validateUpdate(ctx);

        assertThat(ctx.hasErrors()).isFalse();
    }

    @Test
    void anUpdateLeavingCountryAndZoneAloneIsNotJudged() {
        // Saved before the rule, with free text the country does not know.
        Map<String, Object> original = Map.of("id", 1L, "country", "SG", "timeZone", "UTC+8");
        Map<String, Object> patch = new HashMap<>(Map.of("id", 1L, "name", "Renamed"));
        Map<String, Object> merged = new HashMap<>(original);
        merged.putAll(patch);
        WriteContext ctx = WriteContext.of("Company", AccessType.UPDATE, merged, patch, original);

        validator.validateUpdate(ctx);

        assertThat(ctx.hasErrors()).isFalse();
    }

    @Test
    void clearingTheZoneIsRefused() {
        Map<String, Object> original = Map.of("id", 1L, "country", "SG", "timeZone", "Asia/Singapore");
        Map<String, Object> patch = new HashMap<>();
        patch.put("id", 1L);
        patch.put("timeZone", null);
        Map<String, Object> merged = new HashMap<>(original);
        merged.putAll(patch);
        WriteContext ctx = WriteContext.of("Company", AccessType.UPDATE, merged, patch, original);

        validator.validateUpdate(ctx);

        assertThat(ctx.hasErrors()).isTrue();
    }
}
