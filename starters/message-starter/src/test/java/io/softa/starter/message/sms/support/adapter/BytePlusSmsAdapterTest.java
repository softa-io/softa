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

class BytePlusSmsAdapterTest {

    private static final String SEND_URL = "https://sms.byteplusapi.com/sms/openapi/send_sms";

    private BytePlusSmsAdapter adapter;
    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        adapter = new BytePlusSmsAdapter(builder.build());
    }

    private SmsProviderConfig createConfig() {
        SmsProviderConfig config = new SmsProviderConfig();
        config.setId(1L);
        config.setProviderType(SmsProvider.BYTEPLUS);
        config.setApiKey("746123456");
        config.setApiSecret("otp-password");
        config.setSenderId("Zingkey");
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
    void getProviderReturnsBytePlus() {
        Assertions.assertEquals(SmsProvider.BYTEPLUS, adapter.getProvider());
    }

    @Test
    void sendSuccessReturnsFirstMessageId() {
        mockServer.expect(requestTo(SEND_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", basicAuth("746123456", "otp-password")))
                .andExpect(jsonPath("$.PhoneNumbers").value("+6598620548"))
                .andExpect(jsonPath("$.Content").value("Your code is 123456"))
                .andExpect(jsonPath("$.From").value("Zingkey"))
                .andRespond(withSuccess(
                        """
                        {
                          "ResponseMetadata": {
                            "RequestId": "a8e0788d-334e-49fc-92e8-61b7878c9735",
                            "Region": "ap-singapore-1"
                          },
                          "Result": {"MessageID": ["c0603139-2613-4f17-a9e3-e6faa3846eda"]}
                        }
                        """,
                        MediaType.APPLICATION_JSON));

        SmsSendResult result = adapter.send(createConfig(), request("+6598620548", "Your code is 123456"));

        mockServer.verify();
        Assertions.assertTrue(result.isSuccess());
        Assertions.assertEquals("c0603139-2613-4f17-a9e3-e6faa3846eda", result.getProviderMessageId());
    }

    @Test
    void sendUsesConfiguredEndpointAndFallsBackToSenderNumber() {
        SmsProviderConfig config = createConfig();
        config.setSenderId(null);
        config.setSenderNumber("ZingkeyHR");
        config.setApiEndpoint("https://sms.example.test");

        mockServer.expect(requestTo("https://sms.example.test/sms/openapi/send_sms"))
                .andExpect(jsonPath("$.From").value("ZingkeyHR"))
                .andRespond(withSuccess(
                        """
                        {"Result": {"MessageID": ["m-1"]}}
                        """,
                        MediaType.APPLICATION_JSON));

        SmsSendResult result = adapter.send(config, request("+6598620548", "Hello"));

        mockServer.verify();
        Assertions.assertTrue(result.isSuccess());
        Assertions.assertEquals("m-1", result.getProviderMessageId());
    }

    @Test
    void sendWithoutSenderFailsWithoutCallingBytePlus() {
        SmsProviderConfig config = createConfig();
        config.setSenderId(null);

        SmsSendResult result = adapter.send(config, request("+6598620548", "Hello"));

        mockServer.verify();
        Assertions.assertFalse(result.isSuccess());
        Assertions.assertTrue(result.getErrorMessage().contains("requires a sender"));
    }

    @Test
    void sendTreatsErrorInSuccessfulResponseAsFailure() {
        mockServer.expect(requestTo(SEND_URL))
                .andRespond(withSuccess(
                        """
                        {
                          "ResponseMetadata": {
                            "RequestId": "r-1",
                            "Error": {"Code": "RE:0011", "Message": "Unsupport area"}
                          }
                        }
                        """,
                        MediaType.APPLICATION_JSON));

        SmsSendResult result = adapter.send(createConfig(), request("+6421123456", "Hello"));

        Assertions.assertFalse(result.isSuccess());
        Assertions.assertEquals("RE:0011", result.getErrorCode());
        Assertions.assertEquals("Unsupport area", result.getErrorMessage());
    }

    @Test
    void sendHandlesMissingMessageId() {
        mockServer.expect(requestTo(SEND_URL))
                .andRespond(withSuccess(
                        """
                        {"ResponseMetadata": {"RequestId": "r-2"}, "Result": {"MessageID": []}}
                        """,
                        MediaType.APPLICATION_JSON));

        SmsSendResult result = adapter.send(createConfig(), request("+6598620548", "Hello"));

        Assertions.assertFalse(result.isSuccess());
        Assertions.assertEquals("BytePlus returned no message ID", result.getErrorMessage());
    }

    @Test
    void sendParsesHttpErrorBody() {
        mockServer.expect(requestTo(SEND_URL))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                              {"ResponseMetadata": {"Error": {"Code": "RE:0000", "Message": "Authentication failed"}}}
                              """));

        SmsSendResult result = adapter.send(createConfig(), request("+6598620548", "Hello"));

        Assertions.assertFalse(result.isSuccess());
        Assertions.assertEquals("RE:0000", result.getErrorCode());
        Assertions.assertEquals("Authentication failed", result.getErrorMessage());
    }

    @Test
    void sendFallsBackToHttpStatusWhenErrorBodyHasNoError() {
        mockServer.expect(requestTo(SEND_URL))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{}"));

        SmsSendResult result = adapter.send(createConfig(), request("+6598620548", "Hello"));

        Assertions.assertFalse(result.isSuccess());
        Assertions.assertEquals("HTTP_503", result.getErrorCode());
    }
}
