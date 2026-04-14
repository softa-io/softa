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

class CmSmsAdapterTest {

    private CmSmsAdapter adapter;
    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        adapter = new CmSmsAdapter(restClient);
    }

    private SmsProviderConfig createConfig() {
        SmsProviderConfig config = new SmsProviderConfig();
        config.setId(1L);
        config.setProviderType(SmsProvider.CM);
        config.setApiKey("cm-product-token-uuid");
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
    void getProviderReturnsCm() {
        Assertions.assertEquals(SmsProvider.CM, adapter.getProvider());
    }

    @Test
    void sendSuccessWithEmptyBody() {
        SmsProviderConfig config = createConfig();
        String expectedUrl = "https://gw.cmtelecom.com/v1.0/message";

        mockServer.expect(requestTo(expectedUrl))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        SmsSendResult result = adapter.send(config, request("+8613800138000", "Hello CM", null, null, null));

        mockServer.verify();
        Assertions.assertTrue(result.isSuccess());
        Assertions.assertNull(result.getProviderMessageId());
    }

    @Test
    void sendSuccessWithNonErrorJsonBody() {
        SmsProviderConfig config = createConfig();
        String expectedUrl = "https://gw.cmtelecom.com/v1.0/message";

        mockServer.expect(requestTo(expectedUrl))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        """
                        {"status": "accepted"}
                        """,
                        MediaType.APPLICATION_JSON));

        SmsSendResult result = adapter.send(config, request("+8613800138000", "Hello CM", null, null, null));

        mockServer.verify();
        Assertions.assertTrue(result.isSuccess());
    }

    @Test
    void sendFailsWhenResponseContainsDetails() {
        SmsProviderConfig config = createConfig();
        String expectedUrl = "https://gw.cmtelecom.com/v1.0/message";

        mockServer.expect(requestTo(expectedUrl))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        """
                        {"details": "Invalid product token"}
                        """,
                        MediaType.APPLICATION_JSON));

        SmsSendResult result = adapter.send(config, request("+8613800138000", "Hello", null, null, null));

        mockServer.verify();
        Assertions.assertFalse(result.isSuccess());
        Assertions.assertEquals("Invalid product token", result.getErrorMessage());
    }

    @Test
    void sendUsesCustomApiEndpoint() {
        SmsProviderConfig config = createConfig();
        config.setApiEndpoint("https://custom.cmtelecom.com");
        String expectedUrl = "https://custom.cmtelecom.com/v1.0/message";

        mockServer.expect(requestTo(expectedUrl))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        SmsSendResult result = adapter.send(config, request("+8613800138000", "Hello", null, null, null));

        mockServer.verify();
        Assertions.assertTrue(result.isSuccess());
    }

    @Test
    void sendHandlesHttpError() {
        SmsProviderConfig config = createConfig();
        String expectedUrl = "https://gw.cmtelecom.com/v1.0/message";

        mockServer.expect(requestTo(expectedUrl))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                              {"details": "Authentication failed"}
                              """));

        SmsSendResult result = adapter.send(config, request("+8613800138000", "Hello", null, null, null));

        Assertions.assertFalse(result.isSuccess());
        Assertions.assertEquals("Authentication failed", result.getErrorMessage());
    }

    @Test
    void sendUsesSenderIdFallback() {
        SmsProviderConfig config = createConfig();
        config.setSenderNumber(null);
        config.setSenderId("MyCMBrand");
        String expectedUrl = "https://gw.cmtelecom.com/v1.0/message";

        mockServer.expect(requestTo(expectedUrl))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        SmsSendResult result = adapter.send(config, request("+8613800138000", "Hello", null, null, null));

        mockServer.verify();
        Assertions.assertTrue(result.isSuccess());
    }
}
