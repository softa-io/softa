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
 * SMS adapter for the Sinch REST API using {@link RestClient}.
 * <p>
 * Credential mapping:
 * <ul>
 *   <li>{@code apiKey} → Bearer API Token</li>
 *   <li>{@code accountId} → Service Plan ID</li>
 *   <li>{@code senderNumber} → "From" phone number</li>
 *   <li>{@code apiEndpoint} → full base URL override (optional)</li>
 *   <li>{@code extConfig} → optional JSON {@code {"region": "us"}} (values: us, eu, au, br, ca; default: us)</li>
 * </ul>
 *
 * @see <a href="https://developers.sinch.com/docs/sms/api-reference/sms/tag/Batches/#tag/Batches/operation/SendSMS">Sinch API docs</a>
 */
@Slf4j
@Component
public class SinchSmsAdapter extends AbstractSmsProviderAdapter {

    private static final String DEFAULT_REGION = "us";

    public SinchSmsAdapter(RestClient smsRestClient) {
        super(smsRestClient);
    }

    @Override
    public SmsProvider getProvider() {
        return SmsProvider.SINCH;
    }

    @Override
    protected SmsSendResult doSend(SmsProviderConfig config, SmsAdapterRequest request) throws Exception {
        String url = resolveBaseUrl(config) + "/batches";

        Map<String, Object> requestBody = Map.of(
                "from", resolveSender(config),
                "to", List.of(request.getPhoneNumber()),
                "body", request.getContent()
        );

        String jsonBody = JsonUtils.objectToString(requestBody);

        String response = restClient.post()
                .uri(url)
                .header("Authorization", "Bearer " + config.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(jsonBody)
                .retrieve()
                .body(String.class);

        JsonNode json = JsonUtils.stringToObject(response, JsonNode.class);
        return SmsSendResult.success(json.path("id").asString(null));
    }

    @Override
    protected SmsSendResult handleHttpError(RestClientResponseException e) {
        try {
            JsonNode errorJson = JsonUtils.stringToObject(e.getResponseBodyAsString(), JsonNode.class);
            String errorText = errorJson.has("text")
                    ? errorJson.path("text").asString(null)
                    : errorJson.path("message").asString("Unknown Sinch error");
            return SmsSendResult.failure(errorJson.path("code").asString(null), errorText);
        } catch (Exception parseEx) {
            return super.handleHttpError(e);
        }
    }

    /**
     * Resolve the Sinch API base URL.
     * <p>
     * If {@code apiEndpoint} is set, it is used as-is. Otherwise the URL is constructed
     * from the region (defaulting to {@code "us"}) and the service plan ID ({@code accountId}).
     */
    private String resolveBaseUrl(SmsProviderConfig config) {
        if (StringUtils.hasText(config.getApiEndpoint())) {
            return config.getApiEndpoint();
        }

        String region = DEFAULT_REGION;
        if (StringUtils.hasText(config.getExtConfig())) {
            try {
                JsonNode extJson = JsonUtils.stringToObject(config.getExtConfig(), JsonNode.class);
                String extRegion = extJson.path("region").asString(null);
                if (StringUtils.hasText(extRegion)) {
                    region = extRegion;
                }
            } catch (Exception ex) {
                log.warn("Failed to parse Sinch extConfig, using default region '{}': {}",
                        DEFAULT_REGION, ex.getMessage());
            }
        }

        return "https://" + region + ".sms.api.sinch.com/xms/v1/" + config.getAccountId();
    }
}
