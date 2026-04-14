package io.softa.starter.message.sms.support.adapter;

import java.util.LinkedHashMap;
import java.util.Map;
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

import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TencentCloudSmsAdapterTest {

    private TencentCloudSmsAdapter adapter;
    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        adapter = new TencentCloudSmsAdapter(restClient);
    }

    private SmsProviderConfig createConfig() {
        SmsProviderConfig config = new SmsProviderConfig();
        config.setId(1L);
        config.setProviderType(SmsProvider.TENCENT);
        config.setApiKey("AKIDtest123");
        config.setApiSecret("SecretKeyTest456");
        config.setExtConfig("{\"sdkAppId\": \"1400123456\", \"region\": \"ap-guangzhou\"}");
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
    void getProviderReturnsTencent() {
        Assertions.assertEquals(SmsProvider.TENCENT, adapter.getProvider());
    }

    @Test
    void sendSuccessReturnsProviderMessageId() {
        SmsProviderConfig config = createConfig();
        String expectedUrl = "https://sms.tencentcloudapi.com/";

        mockServer.expect(requestTo(expectedUrl))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-TC-Action", "SendSms"))
                .andExpect(header("X-TC-Version", "2021-01-11"))
                .andRespond(withSuccess(
                        """
                        {
                          "Response": {
                            "SendStatusSet": [
                              {
                                "SerialNo": "tencent-serial-001",
                                "PhoneNumber": "+8613800138000",
                                "Fee": 1,
                                "Code": "Ok",
                                "Message": "send success"
                              }
                            ],
                            "RequestId": "req-001"
                          }
                        }
                        """,
                        MediaType.APPLICATION_JSON));

        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("code", "123456");

        SmsSendResult result = adapter.send(config, request("+8613800138000", null,
                "123456", "MySign", vars));

        mockServer.verify();
        Assertions.assertTrue(result.isSuccess());
        Assertions.assertEquals("tencent-serial-001", result.getProviderMessageId());
    }

    @Test
    void sendFailsWithoutExternalTemplateId() {
        SmsProviderConfig config = createConfig();

        SmsSendResult result = adapter.send(config, request("+8613800138000", "Hello",
                null, "MySign", null));

        Assertions.assertFalse(result.isSuccess());
        Assertions.assertEquals("Tencent Cloud SMS requires externalTemplateId (TemplateId)",
                result.getErrorMessage());
    }

    @Test
    void sendHandlesApiLevelError() {
        SmsProviderConfig config = createConfig();
        String expectedUrl = "https://sms.tencentcloudapi.com/";

        mockServer.expect(requestTo(expectedUrl))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        """
                        {
                          "Response": {
                            "Error": {
                              "Code": "AuthFailure.SecretIdNotFound",
                              "Message": "The SecretId is not found"
                            },
                            "RequestId": "req-002"
                          }
                        }
                        """,
                        MediaType.APPLICATION_JSON));

        SmsSendResult result = adapter.send(config, request("+8613800138000", null,
                "123456", "MySign", null));

        mockServer.verify();
        Assertions.assertFalse(result.isSuccess());
        Assertions.assertEquals("AuthFailure.SecretIdNotFound", result.getErrorCode());
        Assertions.assertEquals("The SecretId is not found", result.getErrorMessage());
    }

    @Test
    void sendHandlesSendStatusNotOk() {
        SmsProviderConfig config = createConfig();
        String expectedUrl = "https://sms.tencentcloudapi.com/";

        mockServer.expect(requestTo(expectedUrl))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        """
                        {
                          "Response": {
                            "SendStatusSet": [
                              {
                                "SerialNo": "",
                                "PhoneNumber": "+8613800138000",
                                "Fee": 0,
                                "Code": "LimitExceeded.PhoneNumberDailyLimit",
                                "Message": "The number of SMS messages sent to a single phone exceeds the upper limit"
                              }
                            ],
                            "RequestId": "req-003"
                          }
                        }
                        """,
                        MediaType.APPLICATION_JSON));

        SmsSendResult result = adapter.send(config, request("+8613800138000", null,
                "123456", "MySign", null));

        mockServer.verify();
        Assertions.assertFalse(result.isSuccess());
        Assertions.assertEquals("LimitExceeded.PhoneNumberDailyLimit", result.getErrorCode());
    }

    @Test
    void sendHandlesEmptySendStatusSet() {
        SmsProviderConfig config = createConfig();
        String expectedUrl = "https://sms.tencentcloudapi.com/";

        mockServer.expect(requestTo(expectedUrl))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        """
                        {
                          "Response": {
                            "SendStatusSet": [],
                            "RequestId": "req-004"
                          }
                        }
                        """,
                        MediaType.APPLICATION_JSON));

        SmsSendResult result = adapter.send(config, request("+8613800138000", null,
                "123456", "MySign", null));

        Assertions.assertFalse(result.isSuccess());
        Assertions.assertEquals("Tencent Cloud SMS returned empty SendStatusSet", result.getErrorMessage());
    }

    @Test
    void sendHandlesHttpError() {
        SmsProviderConfig config = createConfig();
        String expectedUrl = "https://sms.tencentcloudapi.com/";

        mockServer.expect(requestTo(expectedUrl))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("Internal Server Error"));

        SmsSendResult result = adapter.send(config, request("+8613800138000", null,
                "123456", "MySign", null));

        Assertions.assertFalse(result.isSuccess());
        Assertions.assertTrue(result.getErrorMessage().contains("HTTP 500"));
    }

    @Test
    void sendWithTemplateVariables() {
        SmsProviderConfig config = createConfig();
        String expectedUrl = "https://sms.tencentcloudapi.com/";

        mockServer.expect(requestTo(expectedUrl))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        """
                        {
                          "Response": {
                            "SendStatusSet": [
                              {
                                "SerialNo": "tencent-serial-002",
                                "PhoneNumber": "+8613800138000",
                                "Fee": 1,
                                "Code": "Ok",
                                "Message": "send success"
                              }
                            ],
                            "RequestId": "req-005"
                          }
                        }
                        """,
                        MediaType.APPLICATION_JSON));

        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("code", "654321");
        vars.put("time", "5");

        SmsSendResult result = adapter.send(config, request("+8613800138000", null,
                "TPL001", "MySign", vars));

        mockServer.verify();
        Assertions.assertTrue(result.isSuccess());
        Assertions.assertEquals("tencent-serial-002", result.getProviderMessageId());
    }

    // --- TC3-HMAC-SHA256 Signature Algorithm Tests ---

    @Test
    void generateAuthorizationProducesValidFormat() {
        String secretId = "AKIDtest123";
        String secretKey = "SecretKeyTest456";
        String service = "sms";
        String host = "sms.tencentcloudapi.com";
        String timestamp = "1551113065";
        String payload = "{\"PhoneNumberSet\":[\"+8613800138000\"],\"SmsSdkAppId\":\"1400123456\"}";

        String authorization = adapter.generateAuthorization(secretId, secretKey, service, host, timestamp, payload);

        Assertions.assertTrue(authorization.startsWith("TC3-HMAC-SHA256 Credential="));
        Assertions.assertTrue(authorization.contains("AKIDtest123/2019-02-25/sms/tc3_request"));
        Assertions.assertTrue(authorization.contains("SignedHeaders=content-type;host"));
        Assertions.assertTrue(authorization.contains("Signature="));
    }

    @Test
    void generateAuthorizationIsConsistentForSameInputs() {
        String secretId = "AKIDtest";
        String secretKey = "SecretKey";
        String service = "sms";
        String host = "sms.tencentcloudapi.com";
        String timestamp = "1609459200";
        String payload = "{}";

        String auth1 = adapter.generateAuthorization(secretId, secretKey, service, host, timestamp, payload);
        String auth2 = adapter.generateAuthorization(secretId, secretKey, service, host, timestamp, payload);

        Assertions.assertEquals(auth1, auth2);
    }

    @Test
    void sha256HexProducesCorrectHash() throws Exception {
        String hash = adapter.sha256Hex("");
        Assertions.assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hash);
    }

    @Test
    void hmacSha256ProducesNonNullResult() throws Exception {
        byte[] key = "testkey".getBytes();
        byte[] result = adapter.hmacSha256(key, "testdata");
        Assertions.assertNotNull(result);
        Assertions.assertEquals(32, result.length);
    }
}
