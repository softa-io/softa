package io.softa.starter.ai.adapter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

import io.softa.framework.base.utils.SpringContextUtils;
import io.softa.starter.ai.enums.AiModelProvider;

/**
 * AI Adapter Factory
 */
@Component
public class AiAdapterFactory {

    private final Map<AiModelProvider, AiAdapter> aiAdapterMap = new ConcurrentHashMap<>();

    /**
     * Get AI Adapter by model provider
     * @param aiProvider AI model provider
     * @return AI adapter
     */
    public AiAdapter getAiAdapter(AiModelProvider aiProvider) {
        AiAdapter aiAdapter = aiAdapterMap.get(aiProvider);
        if (aiAdapter != null) {
            return aiAdapter;
        }
        if (AiModelProvider.OPEN_AI.equals(aiProvider) || AiModelProvider.DEEP_SEEK.equals(aiProvider)) {
            aiAdapter = SpringContextUtils.getBeanByClass(OpenAIAdapter.class);
        } else {
            throw new IllegalArgumentException("AI model provider not yet supported:" + aiProvider);
        }
        aiAdapterMap.put(aiProvider, aiAdapter);
        return aiAdapter;
    }
}
