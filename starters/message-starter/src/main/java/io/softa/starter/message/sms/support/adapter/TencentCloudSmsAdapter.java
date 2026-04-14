package io.softa.starter.message.sms.support.adapter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * SMS adapter for the Tencent Cloud SMS REST API using {@link RestClient}.
 * <p>
 * Authenticates requests using the TC3-HMAC-SHA256 signing algorithm.
 * <p>
 * Credential mapping:
 * <ul>
 *   <li>{@code apiKey} &rarr; Tencent Cloud SecretId</li>
 *   <li>{@code apiSecret} &rarr; Tencent Cloud SecretKey</li>
 *   <li>{@code extConfig} &rarr; JSON: {@code {"sdkAppId": "1400xxx", "region": "ap-guangzhou"}}</li>
 *   <li>{@code signName} parameter &rarr; SMS SignName</li>
 *   <li>{@code externalTemplateId} parameter &rarr; TemplateId (required)</li>
 *   <li>{@code templateVariables} parameter &rarr; TemplateParamSet (values converted to string array)</li>
 * </ul>
 *
 * @see <a href="https://cloud.tencent.com/document/product/382/55981">Tencent Cloud SMS API docs</a>
 * @see <a href="https://cloud.tencent.com/document/api/382/52071">TC3-HMAC-SHA256 Signature</a>
 */
@Slf4j
@Component
public class TencentCloudSmsAdapter extends AbstractSmsProviderAdapter {

    private static final String DEFAULT_HOST = "sms.tencentcloudapi.com";
    private static final String SERVICE = "sms";
    private static final String ACTION = "SendSms";
    private static final String API_VERSION = "2021-01-11";
    private static final String DEFAULT_REGION = "ap-guangzhou";

    public TencentCloudSmsAdapter(RestClient smsRestClient) {
        super(smsRestClient);
    }

    @Override
    public SmsProvider getProvider() {
        return SmsProvider.TENCENT;
    }

    @Override
    protected SmsSendResult doSend(SmsProviderConfig config, SmsAdapterRequest request) throws Exception {
        if (!StringUtils.hasText(request.getExternalTemplateId())) {
            return SmsSendResult.failure(null, "Tencent Cloud SMS requires externalTemplateId (TemplateId)");
        }

        String secretId = config.getApiKey();
        String secretKey = config.getApiSecret();

        String sdkAppId = "";
        String region = DEFAULT_REGION;
        if (StringUtils.hasText(config.getExtConfig())) {
            JsonNode extNode = JsonUtils.stringToObject(config.getExtConfig(), JsonNode.class);
            sdkAppId = extNode.path("sdkAppId").asString("");
            String configRegion = extNode.path("region").asString("");
            if (StringUtils.hasText(configRegion)) {
                region = configRegion;
            }
        }

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("PhoneNumberSet", List.of(request.getPhoneNumber()));
        requestBody.put("SmsSdkAppId", sdkAppId);
        if (StringUtils.hasText(request.getSignName())) {
            requestBody.put("SignName", request.getSignName());
        }
        requestBody.put("TemplateId", request.getExternalTemplateId());
        if (request.getTemplateVariables() != null && !request.getTemplateVariables().isEmpty()) {
            List<String> paramSet = new ArrayList<>();
            for (Object value : request.getTemplateVariables().values()) {
                paramSet.add(value != null ? value.toString() : "");
            }
            requestBody.put("TemplateParamSet", paramSet);
        }

        String payload = JsonUtils.objectToString(requestBody);
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String host = DEFAULT_HOST;
        String authorization = generateAuthorization(secretId, secretKey, SERVICE, host, timestamp, payload);
        String url = "https://" + host + "/";

        String response = restClient.post()
                .uri(url)
                .header("Authorization", authorization)
                .header("X-TC-Action", ACTION)
                .header("X-TC-Version", API_VERSION)
                .header("X-TC-Timestamp", timestamp)
                .header("X-TC-Region", region)
                .header("Host", host)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(String.class);

        JsonNode json = JsonUtils.stringToObject(response, JsonNode.class);
        JsonNode responseNode = json.path("Response");

        JsonNode errorNode = responseNode.path("Error");
        if (!errorNode.isMissingNode()) {
            String errCode = errorNode.path("Code").asString(null);
            String errMsg = errorNode.path("Message").asString("Tencent Cloud API error");
            log.error("Tencent Cloud SMS API error: {} - {}", errCode, errMsg);
            return SmsSendResult.failure(errCode, errMsg);
        }

        JsonNode statusSet = responseNode.path("SendStatusSet");
        if (statusSet.isArray() && !statusSet.isEmpty()) {
            JsonNode firstStatus = statusSet.get(0);
            String code = firstStatus.path("Code").asString("");
            if ("Ok".equals(code)) {
                return SmsSendResult.success(firstStatus.path("SerialNo").asString(null));
            }
            String errMsg = firstStatus.path("Message").asString("Tencent Cloud SMS send failed");
            log.error("Tencent Cloud SMS send failed: {} - {}", code, errMsg);
            return SmsSendResult.failure(code, errMsg);
        }
        return SmsSendResult.failure(null, "Tencent Cloud SMS returned empty SendStatusSet");
    }

    /**
     * Generates the TC3-HMAC-SHA256 Authorization header value.
     *
     * @param secretId  Tencent Cloud SecretId
     * @param secretKey Tencent Cloud SecretKey
     * @param service   the service name (e.g. "sms")
     * @param host      the API host (e.g. "sms.tencentcloudapi.com")
     * @param timestamp Unix timestamp string
     * @param payload   the JSON request body
     * @return the full Authorization header value
     */
    String generateAuthorization(String secretId, String secretKey, String service,
                                 String host, String timestamp, String payload) {
        try {
            String date = Instant.ofEpochSecond(Long.parseLong(timestamp))
                    .atOffset(ZoneOffset.UTC)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));

            String httpRequestMethod = "POST";
            String canonicalUri = "/";
            String canonicalQueryString = "";
            String canonicalHeaders = "content-type:application/json\nhost:" + host + "\n";
            String signedHeaders = "content-type;host";
            String hashedPayload = sha256Hex(payload);
            String canonicalRequest = httpRequestMethod + "\n"
                    + canonicalUri + "\n"
                    + canonicalQueryString + "\n"
                    + canonicalHeaders + "\n"
                    + signedHeaders + "\n"
                    + hashedPayload;

            String credentialScope = date + "/" + service + "/tc3_request";
            String stringToSign = "TC3-HMAC-SHA256\n"
                    + timestamp + "\n"
                    + credentialScope + "\n"
                    + sha256Hex(canonicalRequest);

            byte[] secretDate = hmacSha256(("TC3" + secretKey).getBytes(StandardCharsets.UTF_8), date);
            byte[] secretService = hmacSha256(secretDate, service);
            byte[] secretSigning = hmacSha256(secretService, "tc3_request");
            String signature = hexEncode(hmacSha256(secretSigning, stringToSign));

            return "TC3-HMAC-SHA256 Credential=" + secretId + "/" + credentialScope
                    + ", SignedHeaders=" + signedHeaders
                    + ", Signature=" + signature;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate TC3-HMAC-SHA256 signature", e);
        }
    }

    byte[] hmacSha256(byte[] key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    String sha256Hex(String data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(data.getBytes(StandardCharsets.UTF_8));
        return hexEncode(hash);
    }

    private String hexEncode(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
