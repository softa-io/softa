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

import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TwilioSmsAdapterTest {

    private TwilioSmsAdapter adapter;
    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        adapter = new TwilioSmsAdapter(restClient);
    }

    private SmsProviderConfig createConfig() {
        SmsProviderConfig config = new SmsProviderConfig();
        config.setId(1L);
        config.setProviderType(SmsProvider.TWILIO);
        config.setApiKey("ACtest123");
        config.setApiSecret("authtoken456");
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
    void getProviderReturnsTwilio() {
        Assertions.assertEquals(SmsProvider.TWILIO, adapter.getProvider());
    }

    @Test
    void sendSuccessReturnsProviderMessageId() {
        SmsProviderConfig config = createConfig();
        String expectedUrl = "https://api.twilio.com/2010-04-01/Accounts/ACtest123/Messages.json";

        mockServer.expect(requestTo(expectedUrl))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        """
                        {"sid": "SM1234567890", "status": "queued"}
                        """,
                        MediaType.APPLICATION_JSON));

        SmsSendResult result = adapter.send(config, request("+8613800138000", "Hello", null, null, null));

        mockServer.verify();
        Assertions.assertTrue(result.isSuccess());
        Assertions.assertEquals("SM1234567890", result.getProviderMessageId());
    }

    @Test
    void sendUsesCustomApiEndpoint() {
        SmsProviderConfig config = createConfig();
        config.setApiEndpoint("https://custom.twilio.com/2010-04-01");
        String expectedUrl = "https://custom.twilio.com/2010-04-01/Accounts/ACtest123/Messages.json";

        mockServer.expect(requestTo(expectedUrl))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        """
                        {"sid": "SM999", "status": "queued"}
                        """,
                        MediaType.APPLICATION_JSON));

        SmsSendResult result = adapter.send(config, request("+8613800138000", "Hello", null, null, null));

        mockServer.verify();
        Assertions.assertTrue(result.isSuccess());
    }

    @Test
    void sendHandlesHttpError() {
        SmsProviderConfig config = createConfig();
        String expectedUrl = "https://api.twilio.com/2010-04-01/Accounts/ACtest123/Messages.json";

        mockServer.expect(requestTo(expectedUrl))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                              {"code": 21211, "message": "Invalid 'To' Phone Number"}
                              """));

        SmsSendResult result = adapter.send(config, request("invalid", "Hello", null, null, null));

        Assertions.assertFalse(result.isSuccess());
        Assertions.assertNotNull(result.getErrorMessage());
    }

    @Test
    void sendUsesSenderIdWhenNoSenderNumber() {
        SmsProviderConfig config = createConfig();
        config.setSenderNumber(null);
        config.setSenderId("MySite");
        String expectedUrl = "https://api.twilio.com/2010-04-01/Accounts/ACtest123/Messages.json";

        mockServer.expect(requestTo(expectedUrl))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        """
                        {"sid": "SM555", "status": "queued"}
                        """,
                        MediaType.APPLICATION_JSON));

        SmsSendResult result = adapter.send(config, request("+8613800138000", "Hello", null, null, null));

        mockServer.verify();
        Assertions.assertTrue(result.isSuccess());
    }
}
