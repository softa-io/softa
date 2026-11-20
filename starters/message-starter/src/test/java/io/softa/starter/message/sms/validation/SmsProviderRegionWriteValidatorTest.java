package io.softa.starter.message.sms.validation;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.service.validation.WriteContext;
import io.softa.starter.referencedata.entity.CountryRegion;
import io.softa.starter.referencedata.service.CountryRegionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A route's country must be in the country table, whichever path writes the route
 * ({@link SmsProviderRegionWriteValidator}).
 */
class SmsProviderRegionWriteValidatorTest {

    private CountryRegionService countries;
    private SmsProviderRegionWriteValidator validator;

    @BeforeEach
    void setUp() {
        countries = mock(CountryRegionService.class);
        validator = new SmsProviderRegionWriteValidator(countries);
        when(countries.findByCode(any())).thenReturn(Optional.empty());
        when(countries.findByCode("SG")).thenReturn(Optional.of(new CountryRegion()));
    }

    private static Map<String, Object> row(Object... keyValues) {
        Map<String, Object> row = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            row.put((String) keyValues[i], keyValues[i + 1]);
        }
        return row;
    }

    @Test
    void appliesToRoutesOnly() {
        assertThat(validator.supports("SmsProviderRegion")).isTrue();
        assertThat(validator.supports("SmsProviderConfig")).isFalse();
    }

    @Test
    void aKnownCountryPasses() {
        WriteContext ctx = WriteContext.of("SmsProviderRegion", AccessType.CREATE,
                row("providerConfigId", 1L, "regionCode", "SG", "priority", 10), null);

        validator.validateCreate(ctx);

        assertThat(ctx.hasErrors()).isFalse();
    }

    @Test
    void anUnknownCountryIsRejectedOnTheField() {
        WriteContext ctx = WriteContext.of("SmsProviderRegion", AccessType.CREATE,
                row("providerConfigId", 1L, "regionCode", "XX", "priority", 10), null);

        validator.validateCreate(ctx);

        assertThat(ctx.errors()).singleElement().satisfies(e -> {
            assertThat(e.field()).isEqualTo("regionCode");
            assertThat(e.message()).contains("XX");
        });
    }

    @Test
    void aMissingCountryIsLeftToTheRequiredCheck() {
        WriteContext ctx = WriteContext.of("SmsProviderRegion", AccessType.CREATE,
                row("providerConfigId", 1L, "priority", 10), null);

        validator.validateCreate(ctx);

        assertThat(ctx.hasErrors()).isFalse();
    }

    @Test
    void anUpdateThatLeavesTheCountryAloneIsNotLookedUp() {
        Map<String, Object> stored = row("id", 5L, "providerConfigId", 1L, "regionCode", "XX", "priority", 10);
        Map<String, Object> merged = new HashMap<>(stored);
        merged.put("priority", 20);
        WriteContext ctx = WriteContext.of("SmsProviderRegion", AccessType.UPDATE, merged, row("priority", 20), stored);

        validator.validateUpdate(ctx);

        assertThat(ctx.hasErrors()).isFalse();
        verify(countries, never()).findByCode("XX");
    }

    @Test
    void anUpdateToAnUnknownCountryIsRejected() {
        Map<String, Object> stored = row("id", 5L, "providerConfigId", 1L, "regionCode", "SG");
        Map<String, Object> merged = new HashMap<>(stored);
        merged.put("regionCode", "ZZ");
        WriteContext ctx = WriteContext.of("SmsProviderRegion", AccessType.UPDATE, merged, row("regionCode", "ZZ"), stored);

        validator.validateUpdate(ctx);

        assertThat(ctx.errors()).singleElement().satisfies(e -> assertThat(e.field()).isEqualTo("regionCode"));
    }
}
