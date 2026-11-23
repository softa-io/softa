package io.softa.starter.message.sms.support.adapter;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import io.softa.framework.web.resilience.TransientHttpException;
import io.softa.starter.message.shared.ErrorClassifier;
import io.softa.starter.message.sms.dto.SmsAdapterRequest;
import io.softa.starter.message.sms.dto.SmsSendResult;
import io.softa.starter.message.sms.entity.SmsProviderConfig;
import io.softa.starter.message.sms.enums.SmsProvider;

/**
 * SMS adapter for the OneWaySMS HTTP API ({@code api.aspx}, API v1.3). It sends the rendered text as it
 * is, so no template binding is needed. In Singapore the gateway is run by EC Web Pte Ltd, an SSIR
 * participating aggregator: a sender ID registered with SGNIC names it as the aggregator.
 * <p>
 * Credential mapping:
 * <ul>
 *   <li>{@code apiKey} → API username, {@code apiSecret} → API password: the API pair from the account's
 *       API page, not the web login</li>
 *   <li>{@code apiEndpoint} → scheme and host of the account's MT URL (default
 *       {@code https://gatewayv5.onewaysms.sg}); the adapter adds {@code /api.aspx}</li>
 *   <li>{@code senderNumber} or {@code senderId} → {@code senderid}, up to 11 letters and digits; not sent
 *       when neither is set</li>
 * </ul>
 * The API takes every parameter, the password included, in the query of a GET. So that the password and
 * the text (a sign-in code, say) reach no log and no send record, a failure that would quote the URL —
 * a connection or read failure, a retryable status the resilient client gave up on — is reported here
 * with the query cut off, instead of reaching the base class.
 * <p>
 * The text goes as {@code languagetype=1} (160 characters a part) when it is printable ASCII and line
 * breaks only; anything else goes as {@code languagetype=2} (70 a part), each UTF-16 unit written as
 * four hex digits. The gateway answers HTTP 200 with a number: positive is the MT ID, negative a refusal
 * ({@code -100} … {@code -600}), recorded as {@code ONEWAYSMS_<number>}. The API has no category, so
 * {@link SmsAdapterRequest#getCategory()} is not sent.
 *
 * @see <a href="https://sms.onewaysms.sg/api.pdf">OneWaySMS HTTP API</a>
 */
@Slf4j
@Component
public class OneWaySmsAdapter extends AbstractSmsProviderAdapter {

    static final String DEFAULT_API_ENDPOINT = "https://gatewayv5.onewaysms.sg";
    static final String SEND_PATH = "/api.aspx";
    /** Prefix of the code a refusal is recorded under: {@code ONEWAYSMS_-600}. */
    public static final String CODE_PREFIX = "ONEWAYSMS_";

    /** The refusals the API documents, by result. */
    private static final Map<Long, String> REFUSALS = Map.of(
            -100L, "API username or password is invalid",
            -200L, "senderid is invalid",
            -300L, "mobileno is invalid",
            -400L, "languagetype is invalid",
            -500L, "the message has invalid characters",
            -600L, "insufficient credit balance");

    /** A query carrying the credentials, wherever an exception message quotes the request URL. */
    private static final Pattern CREDENTIAL_QUERY = Pattern.compile("\\?[^\\s\"']*apipassword=[^\\s\"']*");

    private static final HexFormat HEX = HexFormat.of().withUpperCase();

    public OneWaySmsAdapter(RestClient smsRestClient) {
        super(smsRestClient);
    }

    @Override
    public SmsProvider getProvider() {
        return SmsProvider.ONEWAYSMS;
    }

    @Override
    protected SmsSendResult doSend(SmsProviderConfig config, SmsAdapterRequest request) {
        String content = request.getContent() != null ? request.getContent() : "";
        boolean plain = isPlainText(content);

        Map<String, String> params = new LinkedHashMap<>();
        params.put("apiusername", config.getApiKey());
        params.put("apipassword", config.getApiSecret());
        params.put("mobileno", stripPlusPrefix(request.getPhoneNumber()));
        String sender = resolveSender(config);
        if (StringUtils.hasText(sender)) {
            params.put("senderid", stripPlusPrefix(sender));
        }
        params.put("languagetype", plain ? "1" : "2");
        params.put("message", plain ? content : toHex(content));

        String sendUrl = stripTrailingSlash(resolveBaseUrl(config, DEFAULT_API_ENDPOINT)) + SEND_PATH;
        // Encoded here and sent as it is: the client's URI template leaves '+' alone, which the gateway
        // reads as a space.
        URI uri = URI.create(sendUrl + "?" + query(params));

        String answer;
        try {
            answer = restClient.get().uri(uri).retrieve().body(String.class);
        } catch (RestClientResponseException e) {
            // An HTTP error status: its message quotes the status and body, not the URL.
            throw e;
        } catch (TransientHttpException e) {
            log.warn("{} SMS send failed: HTTP {} after retries", getProvider(), e.getStatusCode());
            return SmsSendResult.failure(ErrorClassifier.HTTP_PREFIX + e.getStatusCode(),
                    getProvider() + " API error (HTTP " + e.getStatusCode() + ")");
        } catch (RestClientException e) {
            // Logged here, as the base class does for an unanswered call, but with the URL's query cut off.
            String reason = redact(e.getMessage());
            log.warn("{} SMS send failed: {}", getProvider(), reason);
            return SmsSendResult.failure(null, getProvider() + " API call failed: " + reason);
        }
        return readAnswer(answer);
    }

    /** The gateway's answer: a positive MT ID, else a refusal. */
    SmsSendResult readAnswer(String answer) {
        String result = answer != null ? answer.trim() : "";
        long value;
        try {
            value = Long.parseLong(result);
        } catch (NumberFormatException e) {
            return SmsSendResult.failure(null, getProvider() + " returned an answer it does not document: "
                    + abbreviate(result));
        }
        if (value > 0) {
            return SmsSendResult.success(result);
        }
        String reason = REFUSALS.getOrDefault(value, "refused the message");
        return SmsSendResult.failure(CODE_PREFIX + value, getProvider() + ": " + reason + " (" + value + ")");
    }

    /** Printable ASCII and line breaks: what goes as {@code languagetype=1}. */
    static boolean isPlainText(String content) {
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if ((c < 0x20 || c > 0x7E) && c != '\n' && c != '\r') {
                return false;
            }
        }
        return true;
    }

    /** {@code languagetype=2}: each UTF-16 unit as four upper-case hex digits ("a" → {@code 0061}). */
    static String toHex(String content) {
        StringBuilder sb = new StringBuilder(content.length() * 4);
        for (int i = 0; i < content.length(); i++) {
            sb.append(HEX.toHexDigits(content.charAt(i)));
        }
        return sb.toString();
    }

    /** Cut the query, credentials and text with it, from an exception message that quotes the request URL. */
    static String redact(String message) {
        return message == null ? null : CREDENTIAL_QUERY.matcher(message).replaceAll("");
    }

    private static String query(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        params.forEach((name, value) -> {
            if (!sb.isEmpty()) {
                sb.append('&');
            }
            sb.append(name).append('=').append(URLEncoder.encode(value != null ? value : "", StandardCharsets.UTF_8));
        });
        return sb.toString();
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String abbreviate(String text) {
        String flat = text.replaceAll("\\s+", " ");
        return flat.length() > 100 ? flat.substring(0, 100) + "…" : flat;
    }
}
