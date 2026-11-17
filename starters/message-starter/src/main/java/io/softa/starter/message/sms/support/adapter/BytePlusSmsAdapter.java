package io.softa.starter.message.sms.support.adapter;

import java.util.HashMap;
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
 * SMS adapter for the BytePlus Messaging "OpenAPI for messaging" endpoint using {@link RestClient}.
 * <p>
 * This is BytePlus's password-authenticated endpoint that sends the rendered text as it is, so the
 * softa template is the content and no template binding is needed. (BytePlus's other API,
 * {@code SendSms}, is AK/SK-signed and template-only; it is not used here.)
 * <p>
 * Credential mapping:
 * <ul>
 *   <li>{@code apiKey} → username — the Message Group ID by default</li>
 *   <li>{@code apiSecret} → the Message Group's OpenAPI password. A password serves one message
 *       type only, OTP or MKT: use the OTP password for verification codes and notifications.</li>
 *   <li>{@code senderId} or {@code senderNumber} → "From" (required): the brand name registered and
 *       approved with BytePlus; an unregistered sender fails the send</li>
 *   <li>{@code apiEndpoint} → API base URL (default: {@code https://sms.byteplusapi.com}; another data
 *       center is obtained from BytePlus support)</li>
 * </ul>
 * Which destinations a Message Group may send to is enabled per account; a destination outside it
 * fails with {@code RE:0011} ("Unsupport area").
 *
 * @see <a href="https://docs.byteplus.com/en/docs/byteplus-sms/docs-openapi-overview">BytePlus OpenAPI for messaging</a>
 */
@Slf4j
@Component
public class BytePlusSmsAdapter extends AbstractSmsProviderAdapter {

    private static final String DEFAULT_API_ENDPOINT = "https://sms.byteplusapi.com";

    public BytePlusSmsAdapter(RestClient smsRestClient) {
        super(smsRestClient);
    }

    @Override
    public SmsProvider getProvider() {
        return SmsProvider.BYTEPLUS;
    }

    @Override
    protected SmsSendResult doSend(SmsProviderConfig config, SmsAdapterRequest request) {
        String sender = StringUtils.hasText(config.getSenderId())
                ? config.getSenderId() : config.getSenderNumber();
        if (!StringUtils.hasText(sender)) {
            return SmsSendResult.failure(null,
                    "BytePlus SMS requires a sender: set the registered brand name as the Sender ID");
        }
        String url = resolveBaseUrl(config, DEFAULT_API_ENDPOINT) + "/sms/openapi/send_sms";

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("PhoneNumbers", request.getPhoneNumber());
        requestBody.put("Content", request.getContent());
        requestBody.put("From", sender);

        JsonNode json = postJson(url, JsonUtils.objectToString(requestBody),
                h -> h.setBasicAuth(config.getApiKey(), config.getApiSecret()));

        JsonNode error = json.path("ResponseMetadata").path("Error");
        if (!error.isMissingNode() && !error.isNull()) {
            return errorResult(error);
        }
        JsonNode messageIds = json.path("Result").path("MessageID");
        if (messageIds.isArray() && !messageIds.isEmpty()) {
            return SmsSendResult.success(messageIds.get(0).asString(null));
        }
        return SmsSendResult.failure(null, "BytePlus returned no message ID");
    }

    @Override
    protected SmsSendResult handleHttpError(RestClientResponseException e) {
        return parseErrorBody(e, err -> {
            JsonNode error = err.path("ResponseMetadata").path("Error");
            return error.isObject() ? errorResult(error) : defaultHttpError(e);
        });
    }

    private static SmsSendResult errorResult(JsonNode error) {
        return SmsSendResult.failure(error.path("Code").asString(null),
                error.path("Message").asString("Unknown BytePlus error"));
    }
}
