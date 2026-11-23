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

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AliyunSmsAdapterTest {

    private static final String DEFAULT_URL = "https://dysmsapi.aliyuncs.com/";

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

        mockServer.expect(requestTo(startsWith(DEFAULT_URL)))
                .andExpect(method(HttpMethod.POST))
                .andExpect(queryParam("PhoneNumbers", "8613800138000"))
                .andExpect(queryParam("SignName", "MySign"))
                .andExpect(queryParam("TemplateCode", "SMS_12345678"))
                .andExpect(queryParam("TemplateParam", "%7B%22code%22%3A%22123456%22%7D"))
                .andExpect(header("x-acs-action", "SendSms"))
                .andExpect(header("x-acs-version", "2017-05-25"))
                .andExpect(header("Authorization", startsWith("ACS3-HMAC-SHA256 Credential=AKID-test-123,")))
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

        mockServer.expect(requestTo(startsWith(DEFAULT_URL)))
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

        mockServer.expect(requestTo(startsWith("https://custom.dysmsapi.aliyuncs.com/?")))
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
    void anHttpErrorIsReadForAliyunsCode() {
        SmsProviderConfig config = createConfig();

        mockServer.expect(requestTo(startsWith(DEFAULT_URL)))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"Code": "isp.RAM_PERMISSION_DENY", "Message": "The RAM user is not authorized.",
                                 "RequestId": "req-003"}
                                """));

        SmsSendResult result = adapter.send(config, request("+8613800138000", null,
                "SMS_12345678", "MySign", null));

        Assertions.assertFalse(result.isSuccess());
        Assertions.assertEquals("isp.RAM_PERMISSION_DENY", result.getErrorCode());
        Assertions.assertEquals("The RAM user is not authorized.", result.getErrorMessage());
    }

    @Test
    void sendHandlesHttpErrorWithoutAliyunsBody() {
        SmsProviderConfig config = createConfig();

        mockServer.expect(requestTo(startsWith(DEFAULT_URL)))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("Internal Server Error"));

        SmsSendResult result = adapter.send(config, request("+8613800138000", null,
                "SMS_12345678", "MySign", null));

        Assertions.assertFalse(result.isSuccess());
        Assertions.assertEquals("HTTP_500", result.getErrorCode());
        Assertions.assertTrue(result.getErrorMessage().contains("HTTP 500"));
    }

    @Test
    void sendWithoutTemplateVariablesSendsNoTemplateParam() {
        SmsProviderConfig config = createConfig();

        mockServer.expect(requestTo(startsWith(DEFAULT_URL)))
                .andExpect(request -> Assertions.assertFalse(
                        request.getURI().getRawQuery().contains("TemplateParam=")))
                .andRespond(withSuccess(
                        """
                        {"Code": "OK", "Message": "OK", "BizId": "aliyun-biz-003"}
                        """,
                        MediaType.APPLICATION_JSON));

        SmsSendResult result = adapter.send(config, request("13800138000", null,
                "SMS_12345678", "MySign", null));

        mockServer.verify();
        Assertions.assertTrue(result.isSuccess());
    }

    @Test
    void aRegionIdInExtConfigIsIgnored() {
        // V3 needs no RegionId: the endpoint is the region. A config written for the old adapter still sends.
        SmsProviderConfig config = createConfig();
        config.setExtConfig("{\"regionId\": \"cn-hangzhou\"}");

        mockServer.expect(requestTo(startsWith(DEFAULT_URL)))
                .andExpect(request -> Assertions.assertFalse(
                        request.getURI().getRawQuery().contains("RegionId=")))
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
}
