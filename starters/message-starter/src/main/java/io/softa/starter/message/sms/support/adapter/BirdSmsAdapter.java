package io.softa.starter.message.sms.support.adapter;

import java.util.List;
import java.util.Map;
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
 * SMS adapter for the Bird (MessageBird) REST API using {@link RestClient}.
 * <p>
 * Credential mapping:
 * <ul>
 *   <li>{@code apiKey} → REST API Access Key</li>
 *   <li>{@code senderNumber} or {@code senderId} → Originator (sender name or number)</li>
 *   <li>{@code apiEndpoint} → API base URL (default: {@code https://rest.messagebird.com})</li>
 * </ul>
 *
 * @see <a href="https://developers.messagebird.com/api/sms-messaging/#send-a-message">Bird (MessageBird) API docs</a>
 */
@Slf4j
@Component
public class BirdSmsAdapter extends AbstractSmsProviderAdapter {

    private static final String DEFAULT_API_ENDPOINT = "https://rest.messagebird.com";

    public BirdSmsAdapter(RestClient smsRestClient) {
        super(smsRestClient);
    }

    @Override
    public SmsProvider getProvider() {
        return SmsProvider.BIRD;
    }

    @Override
    protected SmsSendResult doSend(SmsProviderConfig config, SmsAdapterRequest request) throws Exception {
        String baseUrl = StringUtils.hasText(config.getApiEndpoint())
                ? config.getApiEndpoint() : DEFAULT_API_ENDPOINT;
        String url = baseUrl + "/messages";

        String recipient = stripPlusPrefix(request.getPhoneNumber());

        Map<String, Object> requestBody = Map.of(
                "originator", resolveSender(config),
                "body", request.getContent(),
                "recipients", List.of(recipient)
        );

        String jsonBody = JsonUtils.objectToString(requestBody);

        String response = restClient.post()
                .uri(url)
                .header("Authorization", "AccessKey " + config.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(jsonBody)
                .retrieve()
                .body(String.class);

        JsonNode json = JsonUtils.stringToObject(response, JsonNode.class);
        int totalSent = json.path("recipients").path("totalSentCount").asInt(0);
        if (totalSent > 0) {
            return SmsSendResult.success(json.path("id").asString(null));
        }
        return SmsSendResult.failure(null, "Bird returned 0 sent recipients");
    }

    @Override
    protected SmsSendResult handleHttpError(RestClientResponseException e) {
        try {
            JsonNode errorJson = JsonUtils.stringToObject(e.getResponseBodyAsString(), JsonNode.class);
            JsonNode errors = errorJson.path("errors");
            if (errors.isArray() && !errors.isEmpty()) {
                return SmsSendResult.failure(
                        String.valueOf(errors.get(0).path("code").asInt()),
                        errors.get(0).path("description").asString("Unknown Bird error"));
            }
        } catch (Exception parseEx) {
            // fall through to default
        }
        return super.handleHttpError(e);
    }
}
