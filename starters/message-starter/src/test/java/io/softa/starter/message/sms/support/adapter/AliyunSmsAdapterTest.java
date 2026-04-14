package io.softa.starter.message.sms.support.adapter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import io.softa.starter.message.sms.dto.SmsAdapterRequest;
import io.softa.starter.message.sms.dto.SmsSendResult;
import io.softa.starter.message.sms.entity.SmsProviderConfig;
import io.softa.starter.message.sms.enums.SmsProvider;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AliyunSmsAdapterTest {

    private AliyunSmsAdapter adapter;
    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        adapter = new AliyunSmsAdapter(restClient);
    }

    private SmsProviderConfig createConfig() {
        SmsProviderConfig config = new SmsProviderConfig();
        config.setId(1L);
        config.setProviderType(SmsProvider.ALIYUN);
        config.setApiKey("AKID-test-123");
        config.setApiSecret("AccessSecret-test-456");
        config.setExtConfig("{\"regionId\": \"cn-hangzhou\"}");
        return config;
    }

    private SmsAdapterRequest request(String phoneNumber, String content,
                                      String externalTemplateId, String signName,
                                      Map<String, Object> templateVariables) {
        SmsAdapterRequest req = new SmsAdapterRequest();
        req.setPhoneNumber(phoneNumber);
        req.setContent(content);
        req.setExternalTemplateId(externalTemplateId);
        req.setSignName(signName);
        req.setTemplateVariables(templateVariables);
        return req;
    }

    @Test
    void getProviderReturnsAliyun() {
        Assertions.assertEquals(SmsProvider.ALIYUN, adapter.getProvider());
    }

    @Test
    void sendSuccessReturnsProviderMessageId() {
        SmsProviderConfig config = createConfig();
        String expectedUrl = "https://dysmsapi.aliyuncs.com";

        mockServer.expect(requestTo(expectedUrl))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        """
                        {
                          "Code": "OK",
                          "Message": "OK",
                          "BizId": "aliyun-biz-001",
                          "RequestId": "req-aliyun-001"
                        }
                        """,
                        MediaType.APPLICATION_JSON));

        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("code", "123456");

        SmsSendResult result = adapter.send(config, request("+8613800138000", null,
                "SMS_12345678", "MySign", vars));

        mockServer.verify();
        Assertions.assertTrue(result.isSuccess());
        Assertions.assertEquals("aliyun-biz-001", result.getProviderMessageId());
    }

    @Test
    void sendFailsWithoutExternalTemplateId() {
        SmsProviderConfig config = createConfig();

        SmsSendResult result = adapter.send(config, request("+8613800138000", "Hello",
                null, "MySign", null));

        Assertions.assertFalse(result.isSuccess());
        Assertions.assertEquals("Aliyun SMS requires externalTemplateId (TemplateCode)",
                result.getErrorMessage());
    }

    @Test
    void sendHandlesAliyunApiError() {
        SmsProviderConfig config = createConfig();
        String expectedUrl = "https://dysmsapi.aliyuncs.com";

        mockServer.expect(requestTo(expectedUrl))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        """
                        {
                          "Code": "isv.BUSINESS_LIMIT_CONTROL",
                          "Message": "Sending frequency exceeds limit",
                          "RequestId": "req-002"
                        }
                        """,
                        MediaType.APPLICATION_JSON));

        SmsSendResult result = adapter.send(config, request("+8613800138000", null,
                "SMS_12345678", "MySign", null));

        mockServer.verify();
        Assertions.assertFalse(result.isSuccess());
        Assertions.assertEquals("isv.BUSINESS_LIMIT_CONTROL", result.getErrorCode());
        Assertions.assertEquals("Sending frequency exceeds limit", result.getErrorMessage());
    }

    @Test
    void sendUsesCustomApiEndpoint() {
        SmsProviderConfig config = createConfig();
        config.setApiEndpoint("https://custom.dysmsapi.aliyuncs.com");
        String expectedUrl = "https://custom.dysmsapi.aliyuncs.com";

        mockServer.expect(requestTo(expectedUrl))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        """
                        {"Code": "OK", "Message": "OK", "BizId": "aliyun-biz-002"}
                        """,
                        MediaType.APPLICATION_JSON));

        SmsSendResult result = adapter.send(config, request("+8613800138000", null,
                "SMS_12345678", "MySign", null));

        mockServer.verify();
        Assertions.assertTrue(result.isSuccess());
    }

    @Test
    void sendHandlesHttpError() {
        SmsProviderConfig config = createConfig();
        String expectedUrl = "https://dysmsapi.aliyuncs.com";

        mockServer.expect(requestTo(expectedUrl))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("Internal Server Error"));

        SmsSendResult result = adapter.send(config, request("+8613800138000", null,
                "SMS_12345678", "MySign", null));

        Assertions.assertFalse(result.isSuccess());
        Assertions.assertTrue(result.getErrorMessage().contains("HTTP 500"));
    }

    @Test
    void sendWithTemplateVariables() {
        SmsProviderConfig config = createConfig();
        String expectedUrl = "https://dysmsapi.aliyuncs.com";

        mockServer.expect(requestTo(expectedUrl))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        """
                        {"Code": "OK", "Message": "OK", "BizId": "aliyun-biz-003"}
                        """,
                        MediaType.APPLICATION_JSON));

        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("code", "654321");
        vars.put("product", "MyApp");

        SmsSendResult result = adapter.send(config, request("13800138000", null,
                "SMS_12345678", "MySign", vars));

        mockServer.verify();
        Assertions.assertTrue(result.isSuccess());
    }

    @Test
    void sendDefaultsToHangzhouRegionWhenNoExtConfig() {
        SmsProviderConfig config = createConfig();
        config.setExtConfig(null);
        String expectedUrl = "https://dysmsapi.aliyuncs.com";

        mockServer.expect(requestTo(expectedUrl))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        """
                        {"Code": "OK", "Message": "OK", "BizId": "aliyun-biz-004"}
                        """,
                        MediaType.APPLICATION_JSON));

        SmsSendResult result = adapter.send(config, request("+8613800138000", null,
                "SMS_12345678", "MySign", null));

        mockServer.verify();
        Assertions.assertTrue(result.isSuccess());
    }

    // --- POP HMAC-SHA1 Signature Algorithm Tests ---

    @Test
    void generateSignatureProducesConsistentResult() {
        TreeMap<String, String> params = new TreeMap<>();
        params.put("AccessKeyId", "testAKID");
        params.put("Action", "SendSms");
        params.put("Format", "JSON");
        params.put("PhoneNumbers", "13800138000");
        params.put("SignName", "TestSign");
        params.put("SignatureMethod", "HMAC-SHA1");
        params.put("SignatureNonce", "fixed-nonce");
        params.put("SignatureVersion", "1.0");
        params.put("TemplateCode", "SMS_123");
        params.put("Timestamp", "2021-01-01T00:00:00Z");
        params.put("Version", "2017-05-25");

        String sig1 = adapter.generateSignature("testSecret", params);
        String sig2 = adapter.generateSignature("testSecret", params);

        Assertions.assertNotNull(sig1);
        Assertions.assertFalse(sig1.isEmpty());
        Assertions.assertEquals(sig1, sig2);
    }

    @Test
    void generateSignatureChangesWithDifferentSecret() {
        TreeMap<String, String> params = new TreeMap<>();
        params.put("AccessKeyId", "testAKID");
        params.put("Action", "SendSms");
        params.put("Timestamp", "2021-01-01T00:00:00Z");

        String sig1 = adapter.generateSignature("secretA", params);
        String sig2 = adapter.generateSignature("secretB", params);

        Assertions.assertNotEquals(sig1, sig2);
    }

    @Test
    void percentEncodeHandlesSpecialCharacters() {
        Assertions.assertEquals("", adapter.percentEncode(null));
        Assertions.assertEquals("hello", adapter.percentEncode("hello"));
        Assertions.assertEquals("hello%20world", adapter.percentEncode("hello world"));
        Assertions.assertEquals("a%2Ab", adapter.percentEncode("a*b"));
        Assertions.assertEquals("a~b", adapter.percentEncode("a~b"));
        Assertions.assertEquals("%2F", adapter.percentEncode("/"));
    }

    @Test
    void generateSignatureIsBase64Encoded() {
        TreeMap<String, String> params = new TreeMap<>();
        params.put("Action", "SendSms");
        params.put("Timestamp", "2021-01-01T00:00:00Z");

        String sig = adapter.generateSignature("testSecret", params);

        Assertions.assertTrue(sig.matches("[A-Za-z0-9+/=]+"),
                "Signature should be valid Base64: " + sig);
    }
}
