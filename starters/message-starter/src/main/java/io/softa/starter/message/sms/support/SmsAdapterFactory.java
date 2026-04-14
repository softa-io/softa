package io.softa.starter.message.sms.support;

import io.softa.framework.base.exception.BusinessException;
import io.softa.starter.message.sms.enums.SmsProvider;
import io.softa.starter.message.sms.support.adapter.SmsProviderAdapter;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Discovers and dispatches {@link SmsProviderAdapter} beans by provider type.
 * <p>
 * All adapters are auto-discovered via Spring's {@code List<SmsProviderAdapter>} injection
 * and mapped by their {@link SmsProviderAdapter#getProvider()} value at startup.
 * <p>
 * Unlike {@link io.softa.starter.message.mail.support.MailSenderFactory}, adapters are
 * stateless singletons, so no client caching is needed.
 */
@Component
public class SmsAdapterFactory {

    private final Map<SmsProvider, SmsProviderAdapter> adapterMap;

    public SmsAdapterFactory(List<SmsProviderAdapter> adapters) {
        this.adapterMap = adapters.stream()
                .collect(Collectors.toMap(SmsProviderAdapter::getProvider, Function.identity(),
                        (existing, duplicate) -> {
                            throw new BusinessException(
                                    "Duplicate SMS adapter registered for provider: {0}. "
                                    + "Found both {1} and {2}.",
                                    existing.getProvider().getCode(),
                                    existing.getClass().getName(),
                                    duplicate.getClass().getName());
                        }));
    }

    /**
     * Return the adapter for the given provider type.
     *
     * @param provider the SMS provider type
     * @return the matching adapter
     * @throws BusinessException if no adapter is registered for the provider
     */
    public SmsProviderAdapter getAdapter(SmsProvider provider) {
        SmsProviderAdapter adapter = adapterMap.get(provider);
        if (adapter == null) {
            throw new BusinessException(
                    "No SMS adapter registered for provider: {0}. "
                    + "Please ensure the adapter component is on the classpath.", provider.getCode());
        }
        return adapter;
    }
}
