package io.softa.starter.message.sms.support.adapter;

import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

import io.softa.framework.base.utils.JsonUtils;
import io.softa.starter.message.sms.dto.SmsAdapterRequest;
import io.softa.starter.message.sms.dto.SmsSendResult;
import io.softa.starter.message.sms.entity.SmsProviderConfig;
import io.softa.starter.message.sms.enums.SmsProvider;

/**
 * SMS adapter for the CM.com REST API using {@link RestClient}.
 * <p>
 * Credential mapping:
 * <ul>
 *   <li>{@code apiKey} → Product Token (UUID)</li>
 *   <li>{@code senderNumber} or {@code senderId} → "From" identifier</li>
 *   <li>{@code apiEndpoint} → API base URL (default: {@code https://gw.cmtelecom.com})</li>
 * </ul>
 *
 * @see <a href="https://docs.cmtelecom.com/en/api/business-messaging-api/1.0/index#send-a-message">CM.com API docs</a>
 */
@Slf4j
@Component
public class CmSmsAdapter extends AbstractSmsProviderAdapter {

    private static final String DEFAULT_API_ENDPOINT = "https://gw.cmtelecom.com";

    public CmSmsAdapter(RestClient smsRestClient) {
        super(smsRestClient);
    }

    @Override
    public SmsProvider getProvider() {
        return SmsProvider.CM;
    }

    @Override
    protected SmsSendResult doSend(SmsProviderConfig config, SmsAdapterRequest request) {
        String url = resolveBaseUrl(config, DEFAULT_API_ENDPOINT) + "/v1.0/message";

        Map<String, Object> requestBody = Map.of(
                "messages", Map.of(
                        "authentication", Map.of("producttoken", config.getApiKey()),
                        "msg", List.of(Map.of(
                                "from", resolveSender(config),
                                "to", List.of(Map.of("number", request.getPhoneNumber())),
                                "body", Map.of("content", request.getContent())
                        ))
                )
        );

        // CM authenticates via the in-body producttoken (no auth header). A blank/empty
        // body parses to NullNode → neither error key present → treated as accepted.
        JsonNode json = postJson(url, JsonUtils.objectToString(requestBody));
        if (json.has("details") || json.has("messages")) {
            String errorMsg = json.has("details")
                    ? json.path("details").asString("CM send failed")
                    : json.path("messages").toString();
            return SmsSendResult.failure(null, errorMsg);
        }
        return SmsSendResult.success(null);
    }

    @Override
    protected SmsSendResult handleHttpError(RestClientResponseException e) {
        return parseErrorBody(e, err -> err.has("details")
                ? SmsSendResult.failure(null, err.path("details").asString("CM API error"))
                : defaultHttpError(e));
    }
}
