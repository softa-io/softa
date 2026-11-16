package io.softa.starter.message.sms.support.adapter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
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
 * SMS adapter for the ClickSend REST v3 API using {@link RestClient}.
 * <p>
 * Credential mapping:
 * <ul>
 *   <li>{@code apiKey} → API username</li>
 *   <li>{@code apiSecret} → API key</li>
 *   <li>{@code senderNumber} or {@code senderId} → "from" (optional). Left empty, ClickSend uses the
 *       account's default sender for the recipient's country — in New Zealand, where every message
 *       goes out from a registered dedicated short code, that is the short code.</li>
 *   <li>{@code apiEndpoint} → API base URL (default: {@code https://rest.clicksend.com})</li>
 * </ul>
 * A request that ClickSend accepts still carries a status per message: only {@code SUCCESS} means
 * the message was queued, anything else ({@code INVALID_RECIPIENT}, {@code COUNTRY_NOT_ENABLED},
 * {@code REGISTRATION_NEEDED}, ...) is a refusal of that message.
 *
 * @see <a href="https://developers.clicksend.com/docs/messaging/sms/other/send-sms">ClickSend Send SMS</a>
 * @see <a href="https://developers.clicksend.com/docs/#status-codes">ClickSend status codes</a>
 */
@Slf4j
@Component
public class ClickSendSmsAdapter extends AbstractSmsProviderAdapter {

    private static final String DEFAULT_API_ENDPOINT = "https://rest.clicksend.com";
    private static final String SUCCESS = "SUCCESS";
    /** Reported back by ClickSend as the sending application. */
    private static final String SOURCE = "softa";

    public ClickSendSmsAdapter(RestClient smsRestClient) {
        super(smsRestClient);
    }

    @Override
    public SmsProvider getProvider() {
        return SmsProvider.CLICKSEND;
    }

    @Override
    protected SmsSendResult doSend(SmsProviderConfig config, SmsAdapterRequest request) {
        String url = resolveBaseUrl(config, DEFAULT_API_ENDPOINT) + "/v3/sms/send";

        Map<String, Object> message = new HashMap<>();
        message.put("to", request.getPhoneNumber());
        message.put("body", request.getContent());
        message.put("source", SOURCE);
        String sender = resolveSender(config);
        if (StringUtils.hasText(sender)) {
            message.put("from", sender);
        }
        Map<String, Object> requestBody = Map.of("messages", List.of(message));

        JsonNode json = postJson(url, JsonUtils.objectToString(requestBody),
                h -> h.setBasicAuth(config.getApiKey(), config.getApiSecret()));

        JsonNode messages = json.path("data").path("messages");
        if (!messages.isArray() || messages.isEmpty()) {
            return SmsSendResult.failure(json.path("response_code").asString(null),
                    json.path("response_msg").asString("ClickSend returned no message status"));
        }
        JsonNode first = messages.get(0);
        String status = first.path("status").asString("");
        if (SUCCESS.equalsIgnoreCase(status)) {
            return SmsSendResult.success(first.path("message_id").asString(null));
        }
        return SmsSendResult.failure(status, "ClickSend refused the message: " + status);
    }

    @Override
    protected SmsSendResult handleHttpError(RestClientResponseException e) {
        return parseErrorBody(e, err -> SmsSendResult.failure(
                err.path("response_code").asString(null),
                err.path("response_msg").asString("Unknown ClickSend error")));
    }
}
