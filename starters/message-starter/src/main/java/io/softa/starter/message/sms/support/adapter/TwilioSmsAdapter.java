package io.softa.starter.message.sms.support.adapter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

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
 * Twilio uses form-encoding, so it builds its own {@code POST} rather than the
 * base {@code postJson} helper.
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
    protected SmsSendResult doSend(SmsProviderConfig config, SmsAdapterRequest request) {
        String accountSid = config.getApiKey();
        String authToken = config.getApiSecret();
        String url = resolveBaseUrl(config, DEFAULT_API_ENDPOINT)
                + "/Accounts/" + accountSid + "/Messages.json";

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

        return SmsSendResult.success(parseJson(response).path("sid").asString(null));
    }

    @Override
    protected SmsSendResult handleHttpError(RestClientResponseException e) {
        return parseErrorBody(e, err -> SmsSendResult.failure(
                err.path("code").asString(null),
                err.path("message").asString("Unknown Twilio error")));
    }
}
