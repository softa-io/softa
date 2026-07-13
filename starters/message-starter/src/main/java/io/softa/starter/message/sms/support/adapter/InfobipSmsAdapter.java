package io.softa.starter.message.sms.support.adapter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import io.softa.framework.base.utils.JsonUtils;
import io.softa.starter.message.sms.dto.SmsAdapterRequest;
import io.softa.starter.message.sms.dto.SmsSendResult;
import io.softa.starter.message.sms.entity.SmsProviderConfig;
import io.softa.starter.message.sms.enums.SmsProvider;

/**
 * SMS adapter for the Infobip REST API using {@link RestClient}.
 * <p>
 * Credential mapping:
 * <ul>
 *   <li>{@code apiKey} → Infobip API Key</li>
 *   <li>{@code apiEndpoint} → Infobip base URL (e.g. {@code https://xxxxx.api.infobip.com})</li>
 *   <li>{@code senderNumber} or {@code senderId} → "From" identifier</li>
 * </ul>
 *
 * @see <a href="https://www.infobip.com/docs/api/channels/sms/sms-messaging/outbound-sms/send-sms-message">Infobip API docs</a>
 */
@Slf4j
@Component
public class InfobipSmsAdapter extends AbstractSmsProviderAdapter {

    public InfobipSmsAdapter(RestClient smsRestClient) {
        super(smsRestClient);
    }

    @Override
    public SmsProvider getProvider() {
        return SmsProvider.INFOBIP;
    }

    @Override
    protected SmsSendResult doSend(SmsProviderConfig config, SmsAdapterRequest request) {
        String url = config.getApiEndpoint() + "/sms/2/text/advanced";
        String sender = resolveSender(config);

        Map<String, Object> destination = Map.of("to", request.getPhoneNumber());
        Map<String, Object> message = new HashMap<>();
        message.put("destinations", List.of(destination));
        message.put("text", request.getContent());
        if (StringUtils.hasText(sender)) {
            message.put("from", sender);
        }
        Map<String, Object> requestBody = Map.of("messages", List.of(message));

        JsonNode json = postJson(url, JsonUtils.objectToString(requestBody),
                h -> h.set("Authorization", "App " + config.getApiKey()));

        JsonNode messages = json.path("messages");
        if (messages.isArray() && !messages.isEmpty()) {
            JsonNode firstMsg = messages.get(0);
            String statusGroup = firstMsg.path("status").path("groupName").asString("");
            if ("PENDING".equalsIgnoreCase(statusGroup) || "SENT".equalsIgnoreCase(statusGroup)) {
                return SmsSendResult.success(firstMsg.path("messageId").asString(null));
            }
            return SmsSendResult.failure(
                    firstMsg.path("status").path("name").asString(null),
                    firstMsg.path("status").path("description").asString("Infobip send failed"));
        }
        return SmsSendResult.failure(null, "Infobip returned empty messages array");
    }
}
