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

class BirdSmsAdapterTest {

    private BirdSmsAdapter adapter;
    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        adapter = new BirdSmsAdapter(restClient);
    }

    private SmsProviderConfig createConfig() {
        SmsProviderConfig config = new SmsProviderConfig();
        config.setId(1L);
        config.setProviderType(SmsProvider.BIRD);
        config.setApiKey("bird-access-key-123");
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
    void getProviderReturnsBird() {
        Assertions.assertEquals(SmsProvider.BIRD, adapter.getProvider());
    }

    @Test
    void sendSuccessReturnsProviderMessageId() {
        SmsProviderConfig config = createConfig();
        String expectedUrl = "https://rest.messagebird.com/messages";

        mockServer.expect(requestTo(expectedUrl))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "AccessKey bird-access-key-123"))
                .andRespond(withSuccess(
                        """
                        {
                          "id": "bird-msg-001",
                          "recipients": {
                            "totalCount": 1,
                            "totalSentCount": 1,
                            "totalDeliveredCount": 0,
                            "items": [{"recipient": 8613800138000, "status": "sent"}]
                          }
                        }
                        """,
                        MediaType.APPLICATION_JSON));

        SmsSendResult result = adapter.send(config, request("+8613800138000", "Hello Bird", null, null, null));

        mockServer.verify();
        Assertions.assertTrue(result.isSuccess());
        Assertions.assertEquals("bird-msg-001", result.getProviderMessageId());
    }

    @Test
    void sendHandlesZeroSentCount() {
        SmsProviderConfig config = createConfig();
        String expectedUrl = "https://rest.messagebird.com/messages";

        mockServer.expect(requestTo(expectedUrl))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        """
                        {
                          "id": "bird-msg-002",
                          "recipients": {
                            "totalCount": 1,
                            "totalSentCount": 0,
                            "items": [{"recipient": 8613800138000, "status": "delivery_failed"}]
                          }
                        }
                        """,
                        MediaType.APPLICATION_JSON));

        SmsSendResult result = adapter.send(config, request("+8613800138000", "Hello Bird", null, null, null));

        mockServer.verify();
        Assertions.assertFalse(result.isSuccess());
        Assertions.assertEquals("Bird returned 0 sent recipients", result.getErrorMessage());
    }

    @Test
    void sendUsesCustomApiEndpoint() {
        SmsProviderConfig config = createConfig();
        config.setApiEndpoint("https://custom.messagebird.com");
        String expectedUrl = "https://custom.messagebird.com/messages";

        mockServer.expect(requestTo(expectedUrl))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        """
                        {
                          "id": "bird-msg-003",
                          "recipients": {"totalSentCount": 1}
                        }
                        """,
                        MediaType.APPLICATION_JSON));

        SmsSendResult result = adapter.send(config, request("+8613800138000", "Hello", null, null, null));

        mockServer.verify();
        Assertions.assertTrue(result.isSuccess());
    }

    @Test
    void sendHandlesHttpError() {
        SmsProviderConfig config = createConfig();
        String expectedUrl = "https://rest.messagebird.com/messages";

        mockServer.expect(requestTo(expectedUrl))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                              {"errors": [{"code": 9, "description": "no (correct) recipients found", "parameter": "recipients"}]}
                              """));

        SmsSendResult result = adapter.send(config, request("invalid", "Hello", null, null, null));

        Assertions.assertFalse(result.isSuccess());
        Assertions.assertEquals("9", result.getErrorCode());
        Assertions.assertEquals("no (correct) recipients found", result.getErrorMessage());
    }

    @Test
    void sendUsesSenderIdFallback() {
        SmsProviderConfig config = createConfig();
        config.setSenderNumber(null);
        config.setSenderId("MyBrand");
        String expectedUrl = "https://rest.messagebird.com/messages";

        mockServer.expect(requestTo(expectedUrl))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        """
                        {"id": "bird-msg-004", "recipients": {"totalSentCount": 1}}
                        """,
                        MediaType.APPLICATION_JSON));

        SmsSendResult result = adapter.send(config, request("+8613800138000", "Hello", null, null, null));

        mockServer.verify();
        Assertions.assertTrue(result.isSuccess());
    }
}
