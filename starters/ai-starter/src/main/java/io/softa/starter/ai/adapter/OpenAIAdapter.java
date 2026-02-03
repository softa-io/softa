package io.softa.starter.ai.adapter;

import com.plexpt.chatgpt.ChatGPT;
import com.plexpt.chatgpt.ChatGPTStream;
import com.plexpt.chatgpt.entity.chat.ChatCompletion;
import com.plexpt.chatgpt.entity.chat.ChatCompletionResponse;
import com.plexpt.chatgpt.util.Proxys;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.starter.ai.listener.StreamResponseListener;
import io.softa.framework.base.utils.Assert;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OpenAI Adapter
 * Proxy configuration is required according to the situation.
 */
@Component
public class OpenAIAdapter implements AiAdapter {

    /**
     * URL ended with `/`
     */
    @Value("${ai.openai.host:}")
    private String apiHost;

    @Value("${ai.openai.key:}")
    private String apiKey;

    @Value("${ai.openai.keys:}")
    private List<String> apiKeyList;

    @Value("${ai.openai.timeout:60000}")
    private Integer timeout;

    @Value("${ai.openai.proxy.host:}")
    private String proxyHost;

    @Value("${ai.openai.proxy.port:}")
    private Integer proxyPort;

    private static final AtomicReference<ChatGPT> chatGPTInstanceRef = new AtomicReference<>();

    private static final AtomicReference<ChatGPTStream> chatGPTStreamRef = new AtomicReference<>();

    /**
     * Get network proxy
     * @return Proxy
     */
    private Proxy getNetworkProxy() {
        Proxy proxy = Proxy.NO_PROXY;
        if (StringUtils.isNotBlank(proxyHost)) {
            Assert.notNull(proxyPort, "When the Proxy Host is configured, the Proxy Port cannot be empty!");
            proxy = Proxys.http(proxyHost, proxyPort);
        }
        return proxy;
    }

    /**
     * Get ChatGPT instance
     * @return ChatGPT instance
     */
    public ChatGPT getChatGPT() {
        if (chatGPTInstanceRef.get() != null) {
            return chatGPTInstanceRef.get();
        }
        if (StringUtils.isBlank(apiKey) && (apiKeyList == null || apiKeyList.isEmpty())) {
            throw new IllegalArgumentException("OpenAI API key configuration cannot be empty!");
        }
        synchronized (this) {
            if (chatGPTInstanceRef.get() == null) {
                Proxy proxy = this.getNetworkProxy();
                chatGPTInstanceRef.set(
                        ChatGPT.builder()
                                .apiHost(apiHost)
                                .apiKey(apiKey)
                                .apiKeyList(apiKeyList)
                                .proxy(proxy)
                                .timeout(timeout)
                                .build()
                                .init()
                );
            }
        }
        return chatGPTInstanceRef.get();
    }

    /**
     * Get ChatGPTStream instance
     * @return ChatGPTStream instance
     */
    public ChatGPTStream getChatGPTStream() {
        if (chatGPTStreamRef.get() != null) {
            return chatGPTStreamRef.get();
        }
        if (StringUtils.isBlank(apiKey) && (apiKeyList == null || apiKeyList.isEmpty())) {
            throw new IllegalArgumentException("OpenAI API key configuration cannot be empty!");
        }
        synchronized (this) {
            if (chatGPTStreamRef.get() == null) {
                Proxy proxy = this.getNetworkProxy();
                chatGPTStreamRef.set(
                        ChatGPTStream.builder()
                                .apiHost(apiHost)
                                .apiKey(apiKey)
                                .apiKeyList(apiKeyList)
                                .proxy(proxy)
                                .timeout(timeout)
                                .build()
                                .init()
                );
            }
        }
        return chatGPTStreamRef.get();
    }

    /**
     * Stream Response Robot Chat
     * @param chatCompletion Chat request
     * @param listener Stream response listener
     */
    @Override
    public void streamChat(ChatCompletion chatCompletion, StreamResponseListener listener) {
        this.getChatGPTStream().streamChatCompletion(chatCompletion, listener);
    }

    /**
     * Robot Chat
     * @param chatCompletion Chat request
     */
    @Override
    public ChatCompletionResponse chat(ChatCompletion chatCompletion) {
        return this.getChatGPT().chatCompletion(chatCompletion);
    }
}
