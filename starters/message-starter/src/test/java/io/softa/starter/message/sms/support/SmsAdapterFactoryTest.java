package io.softa.starter.message.sms.support;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.softa.framework.base.exception.BusinessException;
import io.softa.starter.message.sms.dto.SmsAdapterRequest;
import io.softa.starter.message.sms.dto.SmsSendResult;
import io.softa.starter.message.sms.entity.SmsProviderConfig;
import io.softa.starter.message.sms.enums.SmsProvider;
import io.softa.starter.message.sms.support.adapter.SmsProviderAdapter;

class SmsAdapterFactoryTest {

    private SmsProviderAdapter createAdapter(SmsProvider provider) {
        return new SmsProviderAdapter() {
            @Override
            public SmsProvider getProvider() {
                return provider;
            }

            @Override
            public SmsSendResult send(SmsProviderConfig config, SmsAdapterRequest request) {
                return SmsSendResult.success(provider.getCode() + "_msg_001");
            }
        };
    }

    @Test
    void getAdapterReturnsCorrectAdapter() {
        SmsProviderAdapter twilioAdapter = createAdapter(SmsProvider.TWILIO);
        SmsProviderAdapter infobipAdapter = createAdapter(SmsProvider.INFOBIP);
        SmsAdapterFactory factory = new SmsAdapterFactory(List.of(twilioAdapter, infobipAdapter));

        Assertions.assertSame(twilioAdapter, factory.getAdapter(SmsProvider.TWILIO));
        Assertions.assertSame(infobipAdapter, factory.getAdapter(SmsProvider.INFOBIP));
    }

    @Test
    void getAdapterThrowsForUnregisteredProvider() {
        SmsProviderAdapter twilioAdapter = createAdapter(SmsProvider.TWILIO);
        SmsAdapterFactory factory = new SmsAdapterFactory(List.of(twilioAdapter));

        Assertions.assertThrows(BusinessException.class, () -> factory.getAdapter(SmsProvider.ALIYUN));
    }

    @Test
    void factoryHandlesEmptyAdapterList() {
        SmsAdapterFactory factory = new SmsAdapterFactory(List.of());

        Assertions.assertThrows(BusinessException.class, () -> factory.getAdapter(SmsProvider.TWILIO));
    }

    @Test
    void factoryHandlesSingleAdapter() {
        SmsProviderAdapter customAdapter = createAdapter(SmsProvider.CUSTOM);
        SmsAdapterFactory factory = new SmsAdapterFactory(List.of(customAdapter));

        Assertions.assertSame(customAdapter, factory.getAdapter(SmsProvider.CUSTOM));
    }

    @Test
    void factoryRegistersAllEightAdapters() {
        // Create one adapter per SmsProvider enum value
        List<SmsProviderAdapter> adapters = Arrays.stream(SmsProvider.values())
                .map(this::createAdapter)
                .collect(Collectors.toList());

        SmsAdapterFactory factory = new SmsAdapterFactory(adapters);

        // Verify all 8 providers are registered and return the correct adapter
        for (SmsProvider provider : SmsProvider.values()) {
            SmsProviderAdapter adapter = factory.getAdapter(provider);
            Assertions.assertNotNull(adapter, "Adapter should be registered for " + provider);
            Assertions.assertEquals(provider, adapter.getProvider(),
                    "Adapter should return correct provider for " + provider);
        }
    }

    @Test
    void factoryRegistersAllEightAdaptersExplicitly() {
        SmsProviderAdapter twilio = createAdapter(SmsProvider.TWILIO);
        SmsProviderAdapter infobip = createAdapter(SmsProvider.INFOBIP);
        SmsProviderAdapter bird = createAdapter(SmsProvider.BIRD);
        SmsProviderAdapter cm = createAdapter(SmsProvider.CM);
        SmsProviderAdapter sinch = createAdapter(SmsProvider.SINCH);
        SmsProviderAdapter aliyun = createAdapter(SmsProvider.ALIYUN);
        SmsProviderAdapter tencent = createAdapter(SmsProvider.TENCENT);
        SmsProviderAdapter custom = createAdapter(SmsProvider.CUSTOM);

        SmsAdapterFactory factory = new SmsAdapterFactory(
                List.of(twilio, infobip, bird, cm, sinch, aliyun, tencent, custom));

        Assertions.assertSame(twilio, factory.getAdapter(SmsProvider.TWILIO));
        Assertions.assertSame(infobip, factory.getAdapter(SmsProvider.INFOBIP));
        Assertions.assertSame(bird, factory.getAdapter(SmsProvider.BIRD));
        Assertions.assertSame(cm, factory.getAdapter(SmsProvider.CM));
        Assertions.assertSame(sinch, factory.getAdapter(SmsProvider.SINCH));
        Assertions.assertSame(aliyun, factory.getAdapter(SmsProvider.ALIYUN));
        Assertions.assertSame(tencent, factory.getAdapter(SmsProvider.TENCENT));
        Assertions.assertSame(custom, factory.getAdapter(SmsProvider.CUSTOM));
    }
}
