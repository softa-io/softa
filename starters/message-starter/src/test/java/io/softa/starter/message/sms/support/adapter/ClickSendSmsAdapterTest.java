package io.softa.starter.message.sms.support.adapter;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
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

class ClickSendSmsAdapterTest {

    private static final String SEND_URL = "https://rest.clicksend.com/v3/sms/send";

    private ClickSendSmsAdapter adapter;
    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        adapter = new ClickSendSmsAdapter(builder.build());
    }

    private SmsProviderConfig createConfig() {
        SmsProviderConfig config = new SmsProviderConfig();
        config.setId(1L);
        config.setProviderType(SmsProvider.CLICKSEND);
        config.setApiKey("api-user");
        config.setApiSecret("api-key-123");
        return config;
    }

    private SmsAdapterRequest request(String phoneNumber, String content) {
        SmsAdapterRequest req = new SmsAdapterRequest();
        req.setPhoneNumber(phoneNumber);
        req.setContent(content);
        return req;
    }

    private static String basicAuth(String user, String password) {
        return "Basic " + Base64.getEncoder()
                .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void getProviderReturnsClickSend() {
        Assertions.assertEquals(SmsProvider.CLICKSEND, adapter.getProvider());
    }

    @Test
    void sendSuccessReturnsMessageId() {
        mockServer.expect(requestTo(SEND_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", basicAuth("api-user", "api-key-123")))
                .andExpect(jsonPath("$.messages[0].to").value("+6421123456"))
                .andExpect(jsonPath("$.messages[0].body").value("Your code is 123456"))
                .andExpect(jsonPath("$.messages[0].source").value("softa"))
                // No sender configured: ClickSend's per-country default sender applies.
                .andExpect(jsonPath("$.messages[0].from").doesNotExist())
                .andRespond(withSuccess(
                        """
                        {
                          "http_code": 200,
                          "response_code": "SUCCESS",
                          "response_msg": "Messages queued for delivery.",
                          "data": {
                            "queued_count": 1,
                            "messages": [
                              {"to": "+6421123456", "message_id": "1EF50711-2787-68F4-8223-9F9C4393E380",
                               "status": "SUCCESS"}
                            ]
                          }
                        }
                        """,
                        MediaType.APPLICATION_JSON));

        SmsSendResult result = adapter.send(createConfig(), request("+6421123456", "Your code is 123456"));

        mockServer.verify();
        Assertions.assertTrue(result.isSuccess());
        Assertions.assertEquals("1EF50711-2787-68F4-8223-9F9C4393E380", result.getProviderMessageId());
    }

    @Test
    void sendPassesConfiguredSenderAndEndpoint() {
        SmsProviderConfig config = createConfig();
        config.setApiEndpoint("https://clicksend.example.test");
        config.setSenderNumber("2255");

        mockServer.expect(requestTo("https://clicksend.example.test/v3/sms/send"))
                .andExpect(jsonPath("$.messages[0].from").value("2255"))
                .andRespond(withSuccess(
                        """
                        {"data": {"messages": [{"message_id": "m-1", "status": "SUCCESS"}]}}
                        """,
                        MediaType.APPLICATION_JSON));

        SmsSendResult result = adapter.send(config, request("+6421123456", "Hello"));

        mockServer.verify();
        Assertions.assertTrue(result.isSuccess());
        Assertions.assertEquals("m-1", result.getProviderMessageId());
    }

    @Test
    void sendTreatsNonSuccessMessageStatusAsFailure() {
        // HTTP 200 with a refused message: the per-message status decides, not response_code.
        mockServer.expect(requestTo(SEND_URL))
                .andRespond(withSuccess(
                        """
                        {
                          "http_code": 200,
                          "response_code": "SUCCESS",
                          "response_msg": "Messages queued for delivery.",
                          "data": {
                            "queued_count": 0,
                            "messages": [
                              {"to": "+6421123456", "message_id": "m-2", "status": "COUNTRY_NOT_ENABLED"}
                            ]
                          }
                        }
                        """,
                        MediaType.APPLICATION_JSON));

        SmsSendResult result = adapter.send(createConfig(), request("+6421123456", "Hello"));

        Assertions.assertFalse(result.isSuccess());
        Assertions.assertEquals("COUNTRY_NOT_ENABLED", result.getErrorCode());
        Assertions.assertEquals("ClickSend refused the message: COUNTRY_NOT_ENABLED", result.getErrorMessage());
    }

    @Test
    void sendHandlesMissingMessages() {
        mockServer.expect(requestTo(SEND_URL))
                .andRespond(withSuccess(
                        """
                        {"http_code": 200, "response_code": "SUCCESS", "response_msg": "OK", "data": {}}
                        """,
                        MediaType.APPLICATION_JSON));

        SmsSendResult result = adapter.send(createConfig(), request("+6421123456", "Hello"));

        Assertions.assertFalse(result.isSuccess());
        Assertions.assertEquals("SUCCESS", result.getErrorCode());
        Assertions.assertEquals("OK", result.getErrorMessage());
    }

    @Test
    void sendParsesHttpErrorBody() {
        mockServer.expect(requestTo(SEND_URL))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                              {"http_code": 401, "response_code": "UNAUTHORIZED",
                               "response_msg": "Authentication credentials were missing or incorrect."}
                              """));

        SmsSendResult result = adapter.send(createConfig(), request("+6421123456", "Hello"));

        Assertions.assertFalse(result.isSuccess());
        Assertions.assertEquals("UNAUTHORIZED", result.getErrorCode());
        Assertions.assertEquals("Authentication credentials were missing or incorrect.", result.getErrorMessage());
    }

    @Test
    void sendFallsBackToHttpStatusWhenErrorBodyIsNotJson() {
        mockServer.expect(requestTo(SEND_URL))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY).body("<html>Bad gateway</html>"));

        SmsSendResult result = adapter.send(createConfig(), request("+6421123456", "Hello"));

        Assertions.assertFalse(result.isSuccess());
        Assertions.assertEquals("HTTP_502", result.getErrorCode());
    }
}
