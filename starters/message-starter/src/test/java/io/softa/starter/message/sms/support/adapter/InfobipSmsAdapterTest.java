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

import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class InfobipSmsAdapterTest {

    private static final String SEND_URL = "https://test.api.infobip.com/sms/3/messages";

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
        config.setSenderNumber("+6582400211");
        return config;
    }

    private SmsAdapterRequest request(String phoneNumber, String content) {
        SmsAdapterRequest req = new SmsAdapterRequest();
        req.setPhoneNumber(phoneNumber);
        req.setContent(content);
        return req;
    }

    private static String acceptedResponse(String messageId, String groupName) {
        return """
               {
                 "bulkId": "bulk-1",
                 "messages": [
                   {
                     "messageId": "%s",
                     "status": {
                       "groupId": 1,
                       "groupName": "%s",
                       "id": 26,
                       "name": "PENDING_ACCEPTED",
                       "description": "Message sent to next instance"
                     },
                     "destination": "6580999185"
                   }
                 ]
               }
               """.formatted(messageId, groupName);
    }

    @Test
    void getProviderReturnsInfobip() {
        Assertions.assertEquals(SmsProvider.INFOBIP, adapter.getProvider());
    }

    @Test
    void sendBuildsV3RequestAndReturnsMessageId() {
        mockServer.expect(requestTo(SEND_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "App infobip-api-key-123"))
                .andExpect(header("Accept", MediaType.APPLICATION_JSON_VALUE))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.messages[0].sender").value("6582400211"))
                .andExpect(jsonPath("$.messages[0].destinations[0].to").value("6580999185"))
                .andExpect(jsonPath("$.messages[0].content.text").value("Your code is 123456"))
                .andExpect(jsonPath("$.messages[0].from").doesNotExist())
                .andExpect(jsonPath("$.messages[0].text").doesNotExist())
                .andRespond(withSuccess(acceptedResponse("msg-infobip-001", "PENDING"),
                        MediaType.APPLICATION_JSON));

        SmsSendResult result = adapter.send(createConfig(), request("+6580999185", "Your code is 123456"));

        mockServer.verify();
        Assertions.assertTrue(result.isSuccess());
        Assertions.assertEquals("msg-infobip-001", result.getProviderMessageId());
    }

    @Test
    void sendUsesAlphanumericSenderIdWhenNoSenderNumber() {
        SmsProviderConfig config = createConfig();
        config.setSenderNumber(null);
        config.setSenderId("Softa");

        mockServer.expect(requestTo(SEND_URL))
                .andExpect(jsonPath("$.messages[0].sender").value("Softa"))
                .andRespond(withSuccess(acceptedResponse("msg-002", "PENDING"), MediaType.APPLICATION_JSON));

        SmsSendResult result = adapter.send(config, request("+6580999185", "Hello"));

        mockServer.verify();
        Assertions.assertTrue(result.isSuccess());
    }

    @Test
    void sendOmitsSenderWhenNoneConfigured() {
        SmsProviderConfig config = createConfig();
        config.setSenderNumber(null);

        mockServer.expect(requestTo(SEND_URL))
                .andExpect(jsonPath("$.messages[0].sender").doesNotExist())
                .andRespond(withSuccess(acceptedResponse("msg-003", "PENDING"), MediaType.APPLICATION_JSON));

        SmsSendResult result = adapter.send(config, request("+6580999185", "Hello"));

        mockServer.verify();
        Assertions.assertTrue(result.isSuccess());
    }

    @Test
    void sendTrimsTrailingSlashOfEndpoint() {
        SmsProviderConfig config = createConfig();
        config.setApiEndpoint("https://test.api.infobip.com/");

        mockServer.expect(requestTo(SEND_URL))
                .andRespond(withSuccess(acceptedResponse("msg-004", "PENDING"), MediaType.APPLICATION_JSON));

        SmsSendResult result = adapter.send(config, request("+6580999185", "Hello"));

        mockServer.verify();
        Assertions.assertTrue(result.isSuccess());
    }

    @Test
    void sendAcceptsDeliveredStatusGroup() {
        mockServer.expect(requestTo(SEND_URL))
                .andRespond(withSuccess(acceptedResponse("msg-005", "DELIVERED"), MediaType.APPLICATION_JSON));

        SmsSendResult result = adapter.send(createConfig(), request("+6580999185", "Hello"));

        Assertions.assertTrue(result.isSuccess());
        Assertions.assertEquals("msg-005", result.getProviderMessageId());
    }

    @Test
    void sendFailsWithoutCallingWhenEndpointMissing() {
        SmsProviderConfig config = createConfig();
        config.setApiEndpoint(" ");

        SmsSendResult result = adapter.send(config, request("+6580999185", "Hello"));

        mockServer.verify();
        Assertions.assertFalse(result.isSuccess());
        Assertions.assertEquals("INFOBIP_ENDPOINT_MISSING", result.getErrorCode());
    }

    @Test
    void sendHandlesRejectedStatus() {
        mockServer.expect(requestTo(SEND_URL))
                .andRespond(withSuccess(
                        """
                        {
                          "messages": [
                            {
                              "messageId": "msg-infobip-006",
                              "status": {
                                "groupId": 5,
                                "groupName": "REJECTED",
                                "name": "REJECTED_PREFIX_MISSING",
                                "description": "Number prefix missing"
                              }
                            }
                          ]
                        }
                        """,
                        MediaType.APPLICATION_JSON));

        SmsSendResult result = adapter.send(createConfig(), request("invalid", "Hello"));

        mockServer.verify();
        Assertions.assertFalse(result.isSuccess());
        Assertions.assertEquals("REJECTED_PREFIX_MISSING", result.getErrorCode());
        Assertions.assertEquals("Number prefix missing", result.getErrorMessage());
    }

    @Test
    void sendHandlesEmptyMessagesArray() {
        mockServer.expect(requestTo(SEND_URL))
                .andRespond(withSuccess("{\"messages\": []}", MediaType.APPLICATION_JSON));

        SmsSendResult result = adapter.send(createConfig(), request("+6580999185", "Hello"));

        Assertions.assertFalse(result.isSuccess());
        Assertions.assertEquals("Infobip returned empty messages array", result.getErrorMessage());
    }

    @Test
    void sendParsesV3ErrorBody() {
        mockServer.expect(requestTo(SEND_URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                              {
                                "errorCode": "E400",
                                "description": "Request cannot be processed.",
                                "action": "Check the syntax, violations and adjust the request.",
                                "violations": [
                                  {"property": "messages[0].destinations[0].to", "violation": "must not be blank"}
                                ]
                              }
                              """));

        SmsSendResult result = adapter.send(createConfig(), request("+6580999185", "Hello"));

        Assertions.assertFalse(result.isSuccess());
        // The code is the HTTP status the retry decision reads; Infobip's own code goes in the message.
        Assertions.assertEquals("HTTP_400", result.getErrorCode());
        Assertions.assertEquals(
                "[E400] Request cannot be processed.: messages[0].destinations[0].to must not be blank",
                result.getErrorMessage());
    }

    @Test
    void sendFallsBackToHttpStatusOnUnknownErrorBody() {
        mockServer.expect(requestTo(SEND_URL))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"unexpected\": true}"));

        SmsSendResult result = adapter.send(createConfig(), request("+6580999185", "Hello"));

        Assertions.assertFalse(result.isSuccess());
        Assertions.assertEquals("HTTP_401", result.getErrorCode());
    }
}
