package io.softa.starter.message.sms.support.adapter;

import java.util.SortedMap;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import io.softa.starter.message.sms.dto.SmsSendResult;
import io.softa.starter.message.sms.enums.SmsProvider;

/**
 * What the two Aliyun SMS adapters share past the signature: making the signed call, and reading a
 * refusal out of the answer.
 * <p>
 * Alibaba Cloud answers an OpenAPI error with {@code Code} / {@code Message} / {@code RequestId}, mostly
 * under HTTP 400 but also 401, 403, 404, 500 — and a few under HTTP 200 ({@code ForbiddenAction},
 * {@code ISV.PRODUCT_UN_SUBSCRIPT}, …). {@code SendMessageToGlobe} reports its own outcome as
 * {@code ResponseCode} / {@code ResponseDescription}. A refusal is read from whichever is there.
 *
 * @see <a href="https://api.alibabacloud.com/document/Dysmsapi/2018-05-01/errorCode">SMS error codes</a>
 */
final class AliyunOpenApi {

    private AliyunOpenApi() {
    }

    /** POST the signed call and return the raw answer; an HTTP error status throws as the client does. */
    static String call(RestClient restClient, String endpoint, String action, String version,
                       SortedMap<String, String> params, String accessKeyId, String accessKeySecret) {
        AliyunAcs3Signer.SignedRequest signed = AliyunAcs3Signer.sign(
                endpoint, action, version, params, accessKeyId, accessKeySecret);
        return restClient.post()
                .uri(signed.uri())
                .headers(h -> signed.headers().forEach(h::set))
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(String.class);
    }

    /**
     * The refusal in an answer: {@code ResponseCode} else {@code Code}, with {@code ResponseDescription}
     * else {@code Message}; null when it carries neither code.
     */
    static SmsSendResult failure(SmsProvider provider, JsonNode json) {
        String code = firstText(json, "ResponseCode", "Code");
        if (code == null) {
            return null;
        }
        String message = firstText(json, "ResponseDescription", "Message");
        return SmsSendResult.failure(code, message != null ? message : provider + " refused the message: " + code);
    }

    private static String firstText(JsonNode json, String first, String second) {
        String value = json.path(first).asString("");
        if (!StringUtils.hasText(value)) {
            value = json.path(second).asString("");
        }
        return StringUtils.hasText(value) ? value : null;
    }
}
