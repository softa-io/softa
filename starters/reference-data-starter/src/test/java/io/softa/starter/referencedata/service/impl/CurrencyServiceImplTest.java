package io.softa.starter.referencedata.service.impl;

import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.domain.FlexQuery;
import io.softa.starter.referencedata.entity.Currency;
import io.softa.starter.referencedata.support.CurrencyCache;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CurrencyServiceImplTest {

    private CurrencyServiceImpl service;
    private CurrencyCache cache;

    @BeforeEach
    void setUp() {
        service = spy(new CurrencyServiceImpl());
        cache = mock(CurrencyCache.class);
        ReflectionTestUtils.setField(service, "cache", cache);

        when(cache.getByCode(any(), any())).thenAnswer(inv -> {
            Supplier<Currency> loader = inv.getArgument(1);
            return loader.get();
        });
    }

    @Test
    void findByCodeReturnsUsd() {
        Currency usd = currency("USD", "840", "US Dollar", "$", 2);
        doReturn(Optional.of(usd)).when(service).searchOne(any(FlexQuery.class));

        Optional<Currency> result = service.findByCode("USD");

        Assertions.assertTrue(result.isPresent());
        Assertions.assertEquals("USD", result.get().getCode());
        Assertions.assertEquals(2, result.get().getDecimalPlaces());
    }

    @Test
    void findByCodeReturnsJpyWithZeroDecimals() {
        Currency jpy = currency("JPY", "392", "Japanese Yen", "¥", 0);
        doReturn(Optional.of(jpy)).when(service).searchOne(any(FlexQuery.class));

        Optional<Currency> result = service.findByCode("JPY");

        Assertions.assertTrue(result.isPresent());
        Assertions.assertEquals(0, result.get().getDecimalPlaces(),
                "JPY must have 0 fraction digits per ISO 4217");
    }

    @Test
    void findByCodeReturnsBhdWithThreeDecimals() {
        Currency bhd = currency("BHD", "048", "Bahraini Dinar", "BD", 3);
        doReturn(Optional.of(bhd)).when(service).searchOne(any(FlexQuery.class));

        Optional<Currency> result = service.findByCode("BHD");

        Assertions.assertTrue(result.isPresent());
        Assertions.assertEquals(3, result.get().getDecimalPlaces(),
                "BHD must have 3 fraction digits per ISO 4217");
    }

    @Test
    void findByCodeReturnsEmptyWhenNotFound() {
        doReturn(Optional.empty()).when(service).searchOne(any(FlexQuery.class));
        Assertions.assertTrue(service.findByCode("XYZ").isEmpty());
    }

    @Test
    void findByCodeCachesAndSkipsDbOnSecondHit() {
        Currency usd = currency("USD", "840", "US Dollar", "$", 2);
        doAnswer(inv -> ((Supplier<Currency>) inv.getArgument(1)).get())
                .doReturn(usd)
                .when(cache).getByCode(any(), any());
        doReturn(Optional.of(usd)).when(service).searchOne(any(FlexQuery.class));

        service.findByCode("USD");
        service.findByCode("USD");

        verify(service, times(1)).searchOne(any(FlexQuery.class));
    }

    // ---- helpers ----

    private static Currency currency(String code, String numericCode, String name,
                                     String symbol, int decimalPlaces) {
        Currency c = new Currency();
        c.setCode(code);
        c.setNumericCode(numericCode);
        c.setName(name);
        c.setSymbol(symbol);
        c.setDecimalPlaces(decimalPlaces);
        return c;
    }
}
