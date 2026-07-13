package io.softa.starter.message.sms.support;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.framework.base.exception.BusinessException;
import io.softa.starter.message.sms.entity.SmsProviderConfig;
import io.softa.starter.message.sms.entity.SmsTemplate;
import io.softa.starter.message.sms.entity.SmsTemplateProviderBinding;
import io.softa.starter.message.sms.service.SmsTemplateProviderBindingService;

/**
 * Builds the final SMS provider plan for one recipient.
 * <p>
 * The planner keeps the model explicit:
 * <ul>
 *   <li>country routing says which provider accounts may serve the recipient;</li>
 *   <li>template-provider bindings supply provider-specific template ids and
 *       sign names, optionally narrowed by region;</li>
 *   <li>the send record stores one selected provider and replays that provider
 *       on retry. Provider switching is not a failure-handling mechanism.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class SmsRoutingPlanner {

    private final SmsProviderDispatcher dispatcher;

    private final SmsTemplateProviderBindingService bindingService;

    /**
     * Build a route from the immutable acceptance snapshot. The template was
     * already resolved by the handler, so rendering and provider-binding
     * selection use the same template snapshot.
     */
    public Plan plan(RoutingRequest request) {
        String phoneNumber = request.phoneNumber();
        if (!StringUtils.hasText(phoneNumber)) {
            throw new BusinessException("Phone number is required for SMS dispatch");
        }

        boolean explicitProvider = request.providerConfigId() != null;
        boolean templateSend = request.template() != null;
        String region = (!explicitProvider || templateSend)
                ? dispatcher.resolveRegion(phoneNumber)
                : null;
        List<SmsProviderConfig> providers = explicitProvider
                ? List.of(dispatcher.resolveProviderById(request.providerConfigId()))
                : dispatcher.resolveProviders(phoneNumber);

        if (!templateSend) {
            return new Plan(providers.getFirst(), request.externalTemplateId(), request.signName(),
                    region, null);
        }

        SmsTemplate template = request.template();
        List<SmsTemplateProviderBinding> bindings = bindingsFor(template.getId());
        if (bindings.isEmpty()) {
            return new Plan(providers.getFirst(), request.externalTemplateId(), request.signName(),
                    region, null);
        }

        BindingMatch match = selectBinding(bindings, providers, region);
        if (match == null) {
            throw new BusinessException(
                    "No SMS template provider binding for template ''{0}'' in region {1}. "
                  + "Eligible provider config ids: {2}.",
                    request.templateCode(), region, providerIds(providers));
        }
        SmsTemplateProviderBinding binding = match.binding();
        return new Plan(match.provider(),
                firstText(binding.getExternalTemplateId(), request.externalTemplateId()),
                firstText(binding.getSignName(), request.signName()),
                region,
                binding);
    }

    private List<SmsTemplateProviderBinding> bindingsFor(Long templateId) {
        List<SmsTemplateProviderBinding> bindings = bindingService.findByTemplateId(templateId);
        if (bindings.isEmpty()) {
            bindings = bindingService.findPlatformBindingsByTemplateId(templateId);
        }
        return bindings;
    }

    private BindingMatch selectBinding(List<SmsTemplateProviderBinding> bindings,
                                       List<SmsProviderConfig> providers,
                                       String region) {
        Map<Long, SmsProviderConfig> providerById = new HashMap<>();
        Map<Long, Integer> providerOrder = new HashMap<>();
        for (int i = 0; i < providers.size(); i++) {
            SmsProviderConfig provider = providers.get(i);
            providerById.put(provider.getId(), provider);
            providerOrder.put(provider.getId(), i);
        }

        Map<Long, BindingMatch> bestByProvider = new HashMap<>();
        for (SmsTemplateProviderBinding binding : bindings) {
            SmsProviderConfig provider = providerById.get(binding.getProviderConfigId());
            if (provider == null) {
                continue;
            }
            boolean exact = StringUtils.hasText(binding.getRegionCode())
                    && binding.getRegionCode().equalsIgnoreCase(region);
            boolean generic = !StringUtils.hasText(binding.getRegionCode());
            if (!exact && !generic) {
                continue;
            }
            BindingMatch candidate = new BindingMatch(binding, provider, exact);
            bestByProvider.merge(provider.getId(), candidate, SmsRoutingPlanner::betterForProvider);
        }

        return bestByProvider.values().stream().min(Comparator.comparingInt((BindingMatch a) -> priority(a.binding())).thenComparingInt(a -> providerOrder.get(a.provider().getId())))
                .orElse(null);
    }

    private static BindingMatch betterForProvider(BindingMatch current, BindingMatch candidate) {
        if (candidate.exact() && !current.exact()) return candidate;
        if (!candidate.exact() && current.exact()) return current;
        return priority(candidate.binding()) < priority(current.binding()) ? candidate : current;
    }

    private static int priority(SmsTemplateProviderBinding binding) {
        return binding.getPriority() != null ? binding.getPriority() : 100;
    }

    private static String firstText(String preferred, String fallback) {
        return StringUtils.hasText(preferred) ? preferred : fallback;
    }

    private static String providerIds(List<SmsProviderConfig> providers) {
        return providers.stream()
                .map(SmsProviderConfig::getId)
                .map(String::valueOf)
                .collect(Collectors.joining(","));
    }

    private record BindingMatch(SmsTemplateProviderBinding binding,
                                SmsProviderConfig provider,
                                boolean exact) {}

    /** Immutable input captured by the acceptance handler before routing. */
    public record RoutingRequest(String phoneNumber,
                                 Long providerConfigId,
                                 String templateCode,
                                 String externalTemplateId,
                                 String signName,
                                 SmsTemplate template) {}

    public record Plan(SmsProviderConfig providerConfig,
                       String externalTemplateId,
                       String signName,
                       String regionCode,
                       SmsTemplateProviderBinding binding) {}
}
