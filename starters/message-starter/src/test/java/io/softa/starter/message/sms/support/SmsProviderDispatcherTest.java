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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers two-tier resolution: precise per-country candidates ordered by region
 * priority, then isDefault catchall, then BusinessException. Also
 * verifies longest-prefix parsing so {@code +886} resolves to TW (not CN).
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
    void preciseRoutingWinsOverDefault() {
        // Aliyun-CN configured for CN; defaults must not be consulted.
        SmsProviderConfig aliyun = enabledConfig(1L, SmsProvider.ALIYUN, false, 10);
        when(regionService.findEnabledByRegion("CN")).thenReturn(List.of(row(1L, 10)));
        when(configService.getById(1L)).thenReturn(Optional.of(aliyun));

        List<SmsProviderConfig> result = dispatcher.resolveProviders("+8613800138000");
        Assertions.assertEquals(List.of(SmsProvider.ALIYUN), providerTypes(result));
        verify(configService, never()).findEnabledDefaults();
    }

    @Test
    void precisePicksHighestRegionPriority() {
        // CN: Aliyun (region priority 10) before Tencent (20).
        SmsProviderConfig aliyun = enabledConfig(1L, SmsProvider.ALIYUN, false, 100);
        SmsProviderConfig tencent = enabledConfig(2L, SmsProvider.TENCENT, false, 100);
        when(regionService.findEnabledByRegion("CN")).thenReturn(List.of(row(1L, 10), row(2L, 20)));
        when(configService.getById(1L)).thenReturn(Optional.of(aliyun));
        when(configService.getById(2L)).thenReturn(Optional.of(tencent));

        List<SmsProviderConfig> result = dispatcher.resolveProviders("+8613800138000");
        Assertions.assertEquals(List.of(SmsProvider.ALIYUN, SmsProvider.TENCENT),
                providerTypes(result));
    }

    @Test
    void fallbackToDefaultWhenNoPreciseRow() {
        SmsProviderConfig twilio = enabledConfig(3L, SmsProvider.TWILIO, true, 1);
        SmsProviderConfig sinch = enabledConfig(4L, SmsProvider.SINCH, true, 2);
        when(regionService.findEnabledByRegion("FR")).thenReturn(List.of());
        when(configService.findEnabledDefaults()).thenReturn(List.of(twilio, sinch));

        List<SmsProviderConfig> result = dispatcher.resolveProviders("+33123456789");
        Assertions.assertEquals(List.of(SmsProvider.TWILIO, SmsProvider.SINCH),
                providerTypes(result));
    }

    @Test
    void throwsWhenNoPreciseAndNoDefault() {
        // +49 = Germany (DE), unambiguous.
        when(regionService.findEnabledByRegion("DE")).thenReturn(List.of());
        when(configService.findEnabledDefaults()).thenReturn(List.of());

        BusinessException ex = Assertions.assertThrows(BusinessException.class,
                () -> dispatcher.resolveProviders("+4915112345678"));
        Assertions.assertTrue(ex.getMessage().contains("DE"),
                "Error should name the unresolved region; was: " + ex.getMessage());
    }

    @Test
    void precisePresentDoesNotConsultDefaults() {
        // TW has Twilio precisely → returns Twilio without touching the default tier.
        SmsProviderConfig twilio = enabledConfig(3L, SmsProvider.TWILIO, true, 1);
        when(regionService.findEnabledByRegion("TW")).thenReturn(List.of(row(3L, 10)));
        when(configService.getById(3L)).thenReturn(Optional.of(twilio));

        List<SmsProviderConfig> result = dispatcher.resolveProviders("+886912345678");
        Assertions.assertEquals(List.of(SmsProvider.TWILIO), providerTypes(result));
        verify(configService, never()).findEnabledDefaults();
    }

    @Test
    void longestPrefixMatchTaiwanNotChina() {
        // +886 must be parsed as TW, not falsely matched by +86 prefix.
        when(regionService.findEnabledByRegion(eq("TW"))).thenReturn(List.of());
        when(configService.findEnabledDefaults()).thenReturn(List.of());

        // No TW precise + no defaults → throws with "TW", NOT "CN".
        BusinessException ex = Assertions.assertThrows(BusinessException.class,
                () -> dispatcher.resolveProviders("+886912345678"));
        Assertions.assertTrue(ex.getMessage().contains("TW"), "Region must parse to TW");
        Assertions.assertFalse(ex.getMessage().contains("CN"), "Must not misparse as CN");
    }

    @Test
    void disabledConfigSkippedEvenIfRegionRowEnabled() {
        // Aliyun has a region row but its config is disabled → next enabled wins.
        SmsProviderConfig aliyunDisabled = enabledConfig(1L, SmsProvider.ALIYUN, false, 10);
        aliyunDisabled.setIsEnabled(false);
        SmsProviderConfig twilio = enabledConfig(2L, SmsProvider.TWILIO, false, 20);
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

    private static SmsProviderConfig enabledConfig(Long id, SmsProvider type, boolean isDefault, int priority) {
        SmsProviderConfig c = new SmsProviderConfig();
        c.setId(id);
        c.setProviderType(type);
        c.setIsDefault(isDefault);
        c.setIsEnabled(true);
        c.setPriority(priority);
        return c;
    }

    private static SmsProviderRegion row(Long providerConfigId, int priority) {
        SmsProviderRegion r = new SmsProviderRegion();
        r.setProviderConfigId(providerConfigId);
        r.setPriority(priority);
        r.setIsEnabled(true);
        return r;
    }
}
