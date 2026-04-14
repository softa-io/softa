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

class InfobipSmsAdapterTest {

    private InfobipSmsAdapter adapter;
    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        adapter = new InfobipSmsAdapter(restClient);
    }

    private SmsProviderConfig createConfig() {
        SmsProviderConfig config = new SmsProviderConfig();
        config.setId(1L);
        config.setProviderType(SmsProvider.INFOBIP);
        config.setApiKey("infobip-api-key-123");
        config.setApiEndpoint("https://test.api.infobip.com");
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
    void getProviderReturnsInfobip() {
        Assertions.assertEquals(SmsProvider.INFOBIP, adapter.getProvider());
    }

    @Test
    void sendSuccessReturnsProviderMessageId() {
        SmsProviderConfig config = createConfig();
        String expectedUrl = "https://test.api.infobip.com/sms/2/text/advanced";

        mockServer.expect(requestTo(expectedUrl))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "App infobip-api-key-123"))
                .andRespond(withSuccess(
                        """
                        {
                          "messages": [
                            {
                              "messageId": "msg-infobip-001",
                              "status": {
                                "groupName": "PENDING",
                                "name": "PENDING_ENROUTE",
                                "description": "Message sent to next instance"
                              }
                            }
                          ]
                        }
                        """,
                        MediaType.APPLICATION_JSON));

        SmsSendResult result = adapter.send(config, request("+8613800138000", "Hello", null, null, null));

        mockServer.verify();
        Assertions.assertTrue(result.isSuccess());
        Assertions.assertEquals("msg-infobip-001", result.getProviderMessageId());
    }

    @Test
    void sendHandlesRejectedStatus() {
        SmsProviderConfig config = createConfig();
        String expectedUrl = "https://test.api.infobip.com/sms/2/text/advanced";

        mockServer.expect(requestTo(expectedUrl))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        """
                        {
                          "messages": [
                            {
                              "messageId": "msg-infobip-002",
                              "status": {
                                "groupName": "REJECTED",
                                "name": "REJECTED_PREFIX_MISSING",
                                "description": "Number prefix missing"
                              }
                            }
                          ]
                        }
                        """,
                        MediaType.APPLICATION_JSON));

        SmsSendResult result = adapter.send(config, request("invalid", "Hello", null, null, null));

        mockServer.verify();
        Assertions.assertFalse(result.isSuccess());
        Assertions.assertEquals("REJECTED_PREFIX_MISSING", result.getErrorCode());
        Assertions.assertEquals("Number prefix missing", result.getErrorMessage());
    }

    @Test
    void sendHandlesEmptyMessagesArray() {
        SmsProviderConfig config = createConfig();
        String expectedUrl = "https://test.api.infobip.com/sms/2/text/advanced";

        mockServer.expect(requestTo(expectedUrl))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        """
                        {"messages": []}
                        """,
                        MediaType.APPLICATION_JSON));

        SmsSendResult result = adapter.send(config, request("+8613800138000", "Hello", null, null, null));

        Assertions.assertFalse(result.isSuccess());
        Assertions.assertEquals("Infobip returned empty messages array", result.getErrorMessage());
    }

    @Test
    void sendHandlesHttpError() {
        SmsProviderConfig config = createConfig();
        String expectedUrl = "https://test.api.infobip.com/sms/2/text/advanced";

        mockServer.expect(requestTo(expectedUrl))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                              {"requestError": {"serviceException": {"text": "Invalid API key"}}}
                              """));

        SmsSendResult result = adapter.send(config, request("+8613800138000", "Hello", null, null, null));

        Assertions.assertFalse(result.isSuccess());
        Assertions.assertNotNull(result.getErrorMessage());
    }

    @Test
    void sendAcceptsSentStatusGroup() {
        SmsProviderConfig config = createConfig();
        String expectedUrl = "https://test.api.infobip.com/sms/2/text/advanced";

        mockServer.expect(requestTo(expectedUrl))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        """
                        {
                          "messages": [
                            {
                              "messageId": "msg-003",
                              "status": {
                                "groupName": "SENT",
                                "name": "SENT_OK",
                                "description": "Message sent successfully"
                              }
                            }
                          ]
                        }
                        """,
                        MediaType.APPLICATION_JSON));

        SmsSendResult result = adapter.send(config, request("+8613800138000", "Hello", null, null, null));

        mockServer.verify();
        Assertions.assertTrue(result.isSuccess());
        Assertions.assertEquals("msg-003", result.getProviderMessageId());
    }
}
