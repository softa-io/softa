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
 * <p>
 * The answer is a {@code MessagesResult} — {@code details}, {@code errorCode} ({@code 0} when accepted) and a
 * {@code messages} list with one entry per recipient ({@code status} {@code Accepted} / {@code Rejected},
 * {@code messageErrorCode}, {@code messageDetails}) — or a list of them. It carries {@code details} and
 * {@code messages} whether the request succeeded or not, so their presence says nothing. HTTP 200 means at
 * least one message was sent; with one recipient per request that is this message, unless the answer
 * itself says it was rejected. A refused request comes back 400 with the same shape. CM returns no
 * message ID.
 *
 * @see <a href="https://developers.cm.com/messaging/reference/messages_sendmessage">CM.com API reference</a>
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

        // CM authenticates via the in-body producttoken (no auth header).
        return readAccepted(postJson(url, JsonUtils.objectToString(requestBody)));
    }

    /**
     * An HTTP 200 answer: accepted unless it says this message was rejected. A blank body, or JSON of
     * another shape, is still a 200, which CM gives only when it sent something, so it is not read as a
     * failure. A body that is not JSON at all fails to parse and is recorded as OUTCOME_UNKNOWN.
     */
    SmsSendResult readAccepted(JsonNode body) {
        JsonNode result = firstResult(body);
        // The message's own status decides; the request's errorCode only when no message is listed.
        String status = result.path("messages").path(0).path("status").asString("");
        boolean rejected = status.isBlank()
                ? result.path("errorCode").asInt(0) != 0
                : "Rejected".equalsIgnoreCase(status);
        if (!rejected) {
            return SmsSendResult.success(null);
        }
        return SmsSendResult.failure(null, reasonOf(result, "CM rejected the message"));
    }

    /**
     * A refused request (400, or 401 / 403 for the product token): the status classifies it, CM's own words
     * say why.
     */
    @Override
    protected SmsSendResult handleHttpError(RestClientResponseException e) {
        SmsSendResult byStatus = defaultHttpError(e);
        return parseErrorBody(e, err -> SmsSendResult.failure(byStatus.getErrorCode(),
                reasonOf(firstResult(err), byStatus.getErrorMessage())));
    }

    /** The answer's result object: the body itself, or the first of a list. */
    private static JsonNode firstResult(JsonNode body) {
        return body != null && body.isArray() ? body.path(0) : body;
    }

    /** The message's own reason, else the request's, else {@code fallback}. */
    private static String reasonOf(JsonNode result, String fallback) {
        String messageDetails = result.path("messages").path(0).path("messageDetails").asString("");
        if (!messageDetails.isBlank()) {
            return "CM: " + messageDetails;
        }
        String details = result.path("details").asString("");
        return details.isBlank() ? fallback : "CM: " + details;
    }
}
