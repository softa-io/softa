package io.softa.starter.message.sms.support.adapter;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import io.softa.framework.base.utils.JsonUtils;
import io.softa.starter.message.sms.dto.SmsAdapterRequest;
import io.softa.starter.message.sms.dto.SmsSendResult;
import io.softa.starter.message.sms.entity.SmsProviderConfig;
import io.softa.starter.message.sms.enums.SmsProvider;

/**
 * SMS adapter for the Alibaba Cloud (Aliyun) SMS REST API using {@link RestClient}.
 * <p>
 * Authenticates requests using the Aliyun POP signature algorithm (HMAC-SHA1).
 * <p>
 * Credential mapping:
 * <ul>
 *   <li>{@code apiKey} &rarr; Aliyun AccessKey ID</li>
 *   <li>{@code apiSecret} &rarr; Aliyun AccessKey Secret</li>
 *   <li>{@code extConfig} &rarr; optional JSON: {@code {"regionId": "cn-hangzhou"}}</li>
 *   <li>{@code signName} parameter &rarr; SMS Signature (required by Aliyun)</li>
 *   <li>{@code externalTemplateId} parameter &rarr; Template Code (required by Aliyun)</li>
 *   <li>{@code templateVariables} parameter &rarr; Template parameters as JSON object</li>
 * </ul>
 *
 * @see <a href="https://help.aliyun.com/document_detail/419273.html">Aliyun SMS SendSms API</a>
 * @see <a href="https://help.aliyun.com/document_detail/315526.html">Aliyun POP Signature</a>
 */
@Slf4j
@Component
public class AliyunSmsAdapter extends AbstractSmsProviderAdapter {

    private static final String DEFAULT_API_ENDPOINT = "https://dysmsapi.aliyuncs.com";
    private static final String DEFAULT_REGION_ID = "cn-hangzhou";
    private static final DateTimeFormatter ISO_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    public AliyunSmsAdapter(RestClient smsRestClient) {
        super(smsRestClient);
    }

    @Override
    public SmsProvider getProvider() {
        return SmsProvider.ALIYUN;
    }

    @Override
    protected SmsSendResult doSend(SmsProviderConfig config, SmsAdapterRequest request) throws Exception {
        if (!StringUtils.hasText(request.getExternalTemplateId())) {
            return SmsSendResult.failure(null, "Aliyun SMS requires externalTemplateId (TemplateCode)");
        }

        String accessKeyId = config.getApiKey();
        String accessKeySecret = config.getApiSecret();

        String regionId = DEFAULT_REGION_ID;
        if (StringUtils.hasText(config.getExtConfig())) {
            try {
                JsonNode extNode = JsonUtils.stringToObject(config.getExtConfig(), JsonNode.class);
                String configRegion = extNode.path("regionId").asString("");
                if (StringUtils.hasText(configRegion)) {
                    regionId = configRegion;
                }
            } catch (Exception e) {
                log.warn("Aliyun SMS: failed to parse extConfig, using default regionId '{}': {}",
                        DEFAULT_REGION_ID, e.getMessage());
            }
        }

        String phone = stripPlusPrefix(request.getPhoneNumber());

        TreeMap<String, String> params = new TreeMap<>();
        params.put("AccessKeyId", accessKeyId);
        params.put("Action", "SendSms");
        params.put("Format", "JSON");
        params.put("PhoneNumbers", phone);
        params.put("RegionId", regionId);
        params.put("SignName", request.getSignName() != null ? request.getSignName() : "");
        params.put("SignatureMethod", "HMAC-SHA1");
        params.put("SignatureNonce", UUID.randomUUID().toString());
        params.put("SignatureVersion", "1.0");
        params.put("TemplateCode", request.getExternalTemplateId());
        params.put("Timestamp", Instant.now().atOffset(ZoneOffset.UTC).format(ISO_FORMATTER));
        params.put("Version", "2017-05-25");

        if (request.getTemplateVariables() != null && !request.getTemplateVariables().isEmpty()) {
            try {
                params.put("TemplateParam", JsonUtils.objectToString(request.getTemplateVariables()));
            } catch (Exception e) {
                log.warn("Aliyun SMS: failed to serialize templateVariables: {}", e.getMessage());
            }
        }

        String signature = generateSignature(accessKeySecret, params);
        params.put("Signature", signature);

        StringBuilder formBody = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!formBody.isEmpty()) {
                formBody.append("&");
            }
            formBody.append(percentEncode(entry.getKey()))
                    .append("=")
                    .append(percentEncode(entry.getValue()));
        }

        String baseUrl = StringUtils.hasText(config.getApiEndpoint())
                ? config.getApiEndpoint() : DEFAULT_API_ENDPOINT;

        String response = restClient.post()
                .uri(baseUrl)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(formBody.toString())
                .retrieve()
                .body(String.class);

        JsonNode json = JsonUtils.stringToObject(response, JsonNode.class);
        String code = json.path("Code").asString("");
        if ("OK".equals(code)) {
            return SmsSendResult.success(json.path("BizId").asString(null));
        }
        log.error("Aliyun SMS send failed: {} - {}", code, json.path("Message").asString(""));
        return SmsSendResult.failure(code, json.path("Message").asString("Aliyun SMS send failed"));
    }

    /**
     * Generates the Aliyun POP HMAC-SHA1 signature.
     * <p>
     * Signature steps:
     * <ol>
     *   <li>Sort all parameters by key</li>
     *   <li>Build canonicalized query string with percent-encoding</li>
     *   <li>StringToSign = {@code POST&%2F&} + percentEncode(canonicalizedQueryString)</li>
     *   <li>Signature = Base64(HMAC-SHA1(accessKeySecret + "&amp;", StringToSign))</li>
     * </ol>
     *
     * @param accessKeySecret the Aliyun AccessKey Secret
     * @param params          all request parameters sorted by key
     * @return the Base64-encoded signature
     */
    String generateSignature(String accessKeySecret, TreeMap<String, String> params) {
        try {
            StringBuilder canonicalized = new StringBuilder();
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (!canonicalized.isEmpty()) {
                    canonicalized.append("&");
                }
                canonicalized.append(percentEncode(entry.getKey()))
                        .append("=")
                        .append(percentEncode(entry.getValue()));
            }

            String stringToSign = "POST&" + percentEncode("/") + "&" + percentEncode(canonicalized.toString());

            String signingKey = accessKeySecret + "&";
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(signingKey.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            byte[] signatureBytes = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));

            return Base64.getEncoder().encodeToString(signatureBytes);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate Aliyun POP signature", e);
        }
    }

    /**
     * Percent-encodes a string according to Aliyun POP specification (RFC 3986).
     * <p>
     * Standard URL encoding with additional replacements:
     * {@code +} → {@code %20}, {@code *} → {@code %2A}, {@code %7E} → {@code ~}.
     *
     * @param value the string to encode
     * @return the percent-encoded string
     */
    String percentEncode(String value) {
        if (value == null) {
            return "";
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }
}
