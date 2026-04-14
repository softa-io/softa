package io.softa.starter.message.sms.support.adapter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
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
 * SMS adapter for the Twilio REST API using {@link RestClient}.
 * <p>
 * Credential mapping:
 * <ul>
 *   <li>{@code apiKey} → Twilio Account SID</li>
 *   <li>{@code apiSecret} → Twilio Auth Token</li>
 *   <li>{@code senderNumber} → "From" phone number</li>
 *   <li>{@code apiEndpoint} → API base URL (default: {@code https://api.twilio.com/2010-04-01})</li>
 * </ul>
 *
 * @see <a href="https://www.twilio.com/docs/messaging/api/message-resource#create-a-message-resource">Twilio API docs</a>
 */
@Slf4j
@Component
public class TwilioSmsAdapter extends AbstractSmsProviderAdapter {

    private static final String DEFAULT_API_ENDPOINT = "https://api.twilio.com/2010-04-01";

    public TwilioSmsAdapter(RestClient smsRestClient) {
        super(smsRestClient);
    }

    @Override
    public SmsProvider getProvider() {
        return SmsProvider.TWILIO;
    }

    @Override
    protected SmsSendResult doSend(SmsProviderConfig config, SmsAdapterRequest request) throws Exception {
        String accountSid = config.getApiKey();
        String authToken = config.getApiSecret();
        String baseUrl = StringUtils.hasText(config.getApiEndpoint())
                ? config.getApiEndpoint() : DEFAULT_API_ENDPOINT;
        String url = baseUrl + "/Accounts/" + accountSid + "/Messages.json";

        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("To", request.getPhoneNumber());
        formData.add("From", resolveSender(config));
        formData.add("Body", request.getContent());

        String response = restClient.post()
                .uri(url)
                .headers(h -> h.setBasicAuth(accountSid, authToken))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(formData)
                .retrieve()
                .body(String.class);

        JsonNode json = JsonUtils.stringToObject(response, JsonNode.class);
        return SmsSendResult.success(json.path("sid").asString(null));
    }

    @Override
    protected SmsSendResult handleHttpError(RestClientResponseException e) {
        try {
            JsonNode err = JsonUtils.stringToObject(e.getResponseBodyAsString(), JsonNode.class);
            return SmsSendResult.failure(
                    err.path("code").asString(null),
                    err.path("message").asString("Unknown Twilio error"));
        } catch (Exception parseEx) {
            return super.handleHttpError(e);
        }
    }
}
