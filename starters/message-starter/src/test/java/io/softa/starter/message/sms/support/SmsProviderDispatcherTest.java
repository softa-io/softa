package io.softa.starter.message.sms.support;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.exception.BusinessException;
import io.softa.starter.message.sms.entity.SmsProviderConfig;
import io.softa.starter.message.sms.entity.SmsProviderRegion;
import io.softa.starter.message.sms.enums.SmsProvider;
import io.softa.starter.message.sms.service.SmsProviderConfigService;
import io.softa.starter.message.sms.service.SmsProviderRegionService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers country routing: the routed, enabled configs ordered by route priority
 * then config id, and BusinessException for a country with no usable route —
 * there is no catchall provider. Also verifies longest-prefix parsing so
 * {@code +886} resolves to TW (not CN).
 */
class SmsProviderDispatcherTest {

    private SmsProviderDispatcher dispatcher;
    private SmsProviderConfigService configService;
    private SmsProviderRegionService regionService;
    private SmsConfigCache configCache;

    @BeforeEach
    void setUp() {
        dispatcher = new SmsProviderDispatcher();
        configService = mock(SmsProviderConfigService.class);
        regionService = mock(SmsProviderRegionService.class);
        configCache = mock(SmsConfigCache.class);

        ReflectionTestUtils.setField(dispatcher, "configService", configService);
        ReflectionTestUtils.setField(dispatcher, "regionService", regionService);
        ReflectionTestUtils.setField(dispatcher, "configCache", configCache);

        // Cache lookups by id pass through to the supplier.
        when(configCache.getById(any(), any())).thenAnswer(inv -> {
            Supplier<SmsProviderConfig> loader = inv.getArgument(1);
            return loader.get();
        });
    }

    @Test
    void routedConfigIsReturned() {
        SmsProviderConfig aliyun = enabledConfig(1L, SmsProvider.ALIYUN);
        when(regionService.findEnabledByRegion("CN")).thenReturn(List.of(row(1L, 10)));
        when(configService.getById(1L)).thenReturn(Optional.of(aliyun));

        List<SmsProviderConfig> result = dispatcher.resolveProviders("+8613800138000");
        Assertions.assertEquals(List.of(SmsProvider.ALIYUN), providerTypes(result));
    }

    @Test
    void picksHighestRoutePriority() {
        // CN: Aliyun (route priority 10) before Tencent (20), whatever the row order.
        SmsProviderConfig aliyun = enabledConfig(1L, SmsProvider.ALIYUN);
        SmsProviderConfig tencent = enabledConfig(2L, SmsProvider.TENCENT);
        when(regionService.findEnabledByRegion("CN")).thenReturn(List.of(row(2L, 20), row(1L, 10)));
        when(configService.getById(1L)).thenReturn(Optional.of(aliyun));
        when(configService.getById(2L)).thenReturn(Optional.of(tencent));

        List<SmsProviderConfig> result = dispatcher.resolveProviders("+8613800138000");
        Assertions.assertEquals(List.of(SmsProvider.ALIYUN, SmsProvider.TENCENT),
                providerTypes(result));
    }

    @Test
    void equalRoutePriorityOrdersByConfigId() {
        SmsProviderConfig tencent = enabledConfig(5L, SmsProvider.TENCENT);
        SmsProviderConfig aliyun = enabledConfig(2L, SmsProvider.ALIYUN);
        when(regionService.findEnabledByRegion("CN")).thenReturn(List.of(row(5L, 10), row(2L, 10)));
        when(configService.getById(5L)).thenReturn(Optional.of(tencent));
        when(configService.getById(2L)).thenReturn(Optional.of(aliyun));

        List<SmsProviderConfig> result = dispatcher.resolveProviders("+8613800138000");
        Assertions.assertEquals(List.of(SmsProvider.ALIYUN, SmsProvider.TENCENT),
                providerTypes(result));
    }

    @Test
    void throwsWhenCountryHasNoRoute() {
        // +49 = Germany (DE), unambiguous. No route row: the country is not supported.
        when(regionService.findEnabledByRegion("DE")).thenReturn(List.of());

        BusinessException ex = Assertions.assertThrows(BusinessException.class,
                () -> dispatcher.resolveProviders("+4915112345678"));
        Assertions.assertTrue(ex.getMessage().contains("DE"),
                "Error should name the unresolved region; was: " + ex.getMessage());
    }

    @Test
    void throwsWhenEveryRoutedConfigIsDisabled() {
        SmsProviderConfig aliyunDisabled = enabledConfig(1L, SmsProvider.ALIYUN);
        aliyunDisabled.setActive(false);
        when(regionService.findEnabledByRegion("CN")).thenReturn(List.of(row(1L, 10)));
        when(configService.getById(1L)).thenReturn(Optional.of(aliyunDisabled));

        BusinessException ex = Assertions.assertThrows(BusinessException.class,
                () -> dispatcher.resolveProviders("+8613800138000"));
        Assertions.assertTrue(ex.getMessage().contains("CN"),
                "Error should name the unresolved region; was: " + ex.getMessage());
    }

    @Test
    void longestPrefixMatchTaiwanNotChina() {
        // +886 must be parsed as TW, not falsely matched by +86 prefix.
        when(regionService.findEnabledByRegion(eq("TW"))).thenReturn(List.of());

        // No TW route → throws with "TW", NOT "CN".
        BusinessException ex = Assertions.assertThrows(BusinessException.class,
                () -> dispatcher.resolveProviders("+886912345678"));
        Assertions.assertTrue(ex.getMessage().contains("TW"), "Region must parse to TW");
        Assertions.assertFalse(ex.getMessage().contains("CN"), "Must not misparse as CN");
    }

    @Test
    void disabledConfigSkippedEvenIfRegionRowEnabled() {
        // Aliyun has a region row but its config is disabled → next enabled wins.
        SmsProviderConfig aliyunDisabled = enabledConfig(1L, SmsProvider.ALIYUN);
        aliyunDisabled.setActive(false);
        SmsProviderConfig twilio = enabledConfig(2L, SmsProvider.TWILIO);
        when(regionService.findEnabledByRegion("CN")).thenReturn(List.of(row(1L, 10), row(2L, 20)));
        when(configService.getById(1L)).thenReturn(Optional.of(aliyunDisabled));
        when(configService.getById(2L)).thenReturn(Optional.of(twilio));

        List<SmsProviderConfig> result = dispatcher.resolveProviders("+8613800138000");
        Assertions.assertEquals(List.of(SmsProvider.TWILIO), providerTypes(result));
    }

    @Test
    void invalidPhoneNumberThrows() {
        Assertions.assertThrows(BusinessException.class,
                () -> dispatcher.resolveProviders("not-a-phone-number"));
    }

    @Test
    void emptyPhoneNumberThrows() {
        Assertions.assertThrows(BusinessException.class,
                () -> dispatcher.resolveProviders(""));
    }

    // ---- helpers ----

    private static List<SmsProvider> providerTypes(List<SmsProviderConfig> providers) {
        return providers.stream().map(SmsProviderConfig::getProviderType).toList();
    }

    private static SmsProviderConfig enabledConfig(Long id, SmsProvider type) {
        SmsProviderConfig c = new SmsProviderConfig();
        c.setId(id);
        c.setProviderType(type);
        c.setActive(true);
        return c;
    }

    private static SmsProviderRegion row(Long providerConfigId, int priority) {
        SmsProviderRegion r = new SmsProviderRegion();
        r.setProviderConfigId(providerConfigId);
        r.setPriority(priority);
        r.setActive(true);
        return r;
    }
}
