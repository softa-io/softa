package io.softa.starter.message.sms.support.adapter;

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

import java.util.Map;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SinchSmsAdapterTest {

    private SinchSmsAdapter adapter;
    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        adapter = new SinchSmsAdapter(restClient);
    }

    private SmsProviderConfig createConfig() {
        SmsProviderConfig config = new SmsProviderConfig();
        config.setId(1L);
        config.setProviderType(SmsProvider.SINCH);
        config.setApiKey("sinch-bearer-token");
        config.setAccountId("plan123");
        config.setSenderNumber("+15551234567");
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
    void getProviderReturnsSinch() {
        Assertions.assertEquals(SmsProvider.SINCH, adapter.getProvider());
    }

    @Test
    void sendSuccessWithDefaultRegion() {
        SmsProviderConfig config = createConfig();
        String expectedUrl = "https://us.sms.api.sinch.com/xms/v1/plan123/batches";

        mockServer.expect(requestTo(expectedUrl))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer sinch-bearer-token"))
                .andRespond(withSuccess(
                        """
                        {"id": "sinch-batch-001", "type": "mt_text"}
                        """,
                        MediaType.APPLICATION_JSON));

        SmsSendResult result = adapter.send(config, request("+8613800138000", "Hello Sinch", null, null, null));

        mockServer.verify();
        Assertions.assertTrue(result.isSuccess());
        Assertions.assertEquals("sinch-batch-001", result.getProviderMessageId());
    }

    @Test
    void sendSuccessWithCustomRegion() {
        SmsProviderConfig config = createConfig();
        config.setExtConfig("{\"region\": \"eu\"}");
        String expectedUrl = "https://eu.sms.api.sinch.com/xms/v1/plan123/batches";

        mockServer.expect(requestTo(expectedUrl))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        """
                        {"id": "sinch-batch-002", "type": "mt_text"}
                        """,
                        MediaType.APPLICATION_JSON));

        SmsSendResult result = adapter.send(config, request("+8613800138000", "Hello EU", null, null, null));

        mockServer.verify();
        Assertions.assertTrue(result.isSuccess());
        Assertions.assertEquals("sinch-batch-002", result.getProviderMessageId());
    }

    @Test
    void sendUsesCustomApiEndpoint() {
        SmsProviderConfig config = createConfig();
        config.setApiEndpoint("https://custom.sinch.com/xms/v1/plan123");
        String expectedUrl = "https://custom.sinch.com/xms/v1/plan123/batches";

        mockServer.expect(requestTo(expectedUrl))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        """
                        {"id": "sinch-batch-003"}
                        """,
                        MediaType.APPLICATION_JSON));

        SmsSendResult result = adapter.send(config, request("+8613800138000", "Hello", null, null, null));

        mockServer.verify();
        Assertions.assertTrue(result.isSuccess());
    }

    @Test
    void sendHandlesHttpError() {
        SmsProviderConfig config = createConfig();
        String expectedUrl = "https://us.sms.api.sinch.com/xms/v1/plan123/batches";

        mockServer.expect(requestTo(expectedUrl))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                              {"code": "forbidden", "text": "Invalid API token"}
                              """));

        SmsSendResult result = adapter.send(config, request("+8613800138000", "Hello", null, null, null));

        Assertions.assertFalse(result.isSuccess());
        Assertions.assertEquals("forbidden", result.getErrorCode());
        Assertions.assertEquals("Invalid API token", result.getErrorMessage());
    }

    @Test
    void sendHandlesInvalidExtConfigGracefully() {
        SmsProviderConfig config = createConfig();
        config.setExtConfig("not valid json");
        String expectedUrl = "https://us.sms.api.sinch.com/xms/v1/plan123/batches";

        mockServer.expect(requestTo(expectedUrl))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        """
                        {"id": "sinch-batch-004"}
                        """,
                        MediaType.APPLICATION_JSON));

        SmsSendResult result = adapter.send(config, request("+8613800138000", "Hello", null, null, null));

        mockServer.verify();
        Assertions.assertTrue(result.isSuccess());
    }

    @Test
    void sendUsesSenderIdFallback() {
        SmsProviderConfig config = createConfig();
        config.setSenderNumber(null);
        config.setSenderId("SinchBrand");
        String expectedUrl = "https://us.sms.api.sinch.com/xms/v1/plan123/batches";

        mockServer.expect(requestTo(expectedUrl))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        """
                        {"id": "sinch-batch-005"}
                        """,
                        MediaType.APPLICATION_JSON));

        SmsSendResult result = adapter.send(config, request("+8613800138000", "Hello", null, null, null));

        mockServer.verify();
        Assertions.assertTrue(result.isSuccess());
    }
}
