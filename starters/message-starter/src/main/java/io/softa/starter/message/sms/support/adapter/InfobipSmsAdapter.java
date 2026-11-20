package io.softa.starter.message.sms.support.adapter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

import io.softa.framework.base.utils.JsonUtils;
import io.softa.starter.message.sms.dto.SmsAdapterRequest;
import io.softa.starter.message.sms.dto.SmsSendResult;
import io.softa.starter.message.sms.entity.SmsProviderConfig;
import io.softa.starter.message.sms.enums.SmsProvider;

/**
 * SMS adapter for the Infobip SMS API v3 ({@code POST /sms/3/messages}) using {@link RestClient}.
 * <p>
 * Credential mapping:
 * <ul>
 *   <li>{@code apiKey} → Infobip API Key, sent as {@code Authorization: App <apiKey>}</li>
 *   <li>{@code apiEndpoint} → the account's own base URL (e.g. {@code https://xxxxx.api.infobip.com});
 *       required, Infobip has no shared default</li>
 *   <li>{@code senderNumber} or {@code senderId} → {@code sender}; a leading {@code +} is dropped</li>
 * </ul>
 * {@code apiSecret}, {@code accountId} and {@code extConfig} are not used. The message is sent as
 * plain text: the template binding's {@code externalTemplateId} and {@code signName} do not apply.
 *
 * @see <a href="https://www.infobip.com/docs/api/channels/sms/outbound-sms/send-message/send-sms-messages">Infobip API docs</a>
 */
@Slf4j
@Component
public class InfobipSmsAdapter extends AbstractSmsProviderAdapter {

    static final String SEND_PATH = "/sms/3/messages";

    /** Status groups of a message Infobip has taken on: queued, accepted, or already delivered. */
    private static final Set<String> ACCEPTED_GROUPS = Set.of("PENDING", "ACCEPTED", "DELIVERED");

    public InfobipSmsAdapter(RestClient smsRestClient) {
        super(smsRestClient);
    }

    @Override
    public SmsProvider getProvider() {
        return SmsProvider.INFOBIP;
    }

    @Override
    protected SmsSendResult doSend(SmsProviderConfig config, SmsAdapterRequest request) {
        if (!StringUtils.hasText(config.getApiEndpoint())) {
            return SmsSendResult.failure("INFOBIP_ENDPOINT_MISSING",
                    "Infobip API Endpoint is not configured: set it to the account's base URL, "
                            + "e.g. https://xxxxx.api.infobip.com");
        }
        String url = trimTrailingSlash(config.getApiEndpoint().trim()) + SEND_PATH;

        Map<String, Object> message = new HashMap<>();
        message.put("destinations", List.of(Map.of("to", stripPlusPrefix(request.getPhoneNumber()))));
        message.put("content", Map.of("text", request.getContent()));
        String sender = resolveSender(config);
        if (StringUtils.hasText(sender)) {
            message.put("sender", stripPlusPrefix(sender));
        }
        Map<String, Object> requestBody = Map.of("messages", List.of(message));

        JsonNode json = postJson(url, JsonUtils.objectToString(requestBody), h -> {
            h.set("Authorization", "App " + config.getApiKey());
            h.setAccept(List.of(MediaType.APPLICATION_JSON));
        });

        JsonNode messages = json.path("messages");
        if (messages.isArray() && !messages.isEmpty()) {
            JsonNode firstMsg = messages.get(0);
            JsonNode status = firstMsg.path("status");
            String statusGroup = status.path("groupName").asString("");
            if (ACCEPTED_GROUPS.contains(statusGroup.toUpperCase())) {
                return SmsSendResult.success(firstMsg.path("messageId").asString(null));
            }
            return SmsSendResult.failure(
                    status.path("name").asString(null),
                    status.path("description").asString("Infobip send failed"));
        }
        return SmsSendResult.failure(null, "Infobip returned empty messages array");
    }

    /**
     * Infobip v3 error body: {@code errorCode}, {@code description}, and for a bad request
     * {@code violations[]} naming the offending {@code property}.
     */
    @Override
    protected SmsSendResult handleHttpError(RestClientResponseException e) {
        return parseErrorBody(e, err -> {
            String errorCode = err.path("errorCode").asString(null);
            String description = err.path("description").asString(null);
            if (errorCode == null && description == null) {
                return defaultHttpError(e);
            }
            StringBuilder message = new StringBuilder(
                    description != null ? description : "Infobip API error");
            JsonNode violation = err.path("violations").path(0);
            if (!violation.isMissingNode()) {
                message.append(": ").append(violation.path("property").asString(""))
                        .append(" ").append(violation.path("violation").asString(""));
            }
            return SmsSendResult.failure(errorCode, message.toString().trim());
        });
    }

    private static String trimTrailingSlash(String url) {
        String result = url;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
