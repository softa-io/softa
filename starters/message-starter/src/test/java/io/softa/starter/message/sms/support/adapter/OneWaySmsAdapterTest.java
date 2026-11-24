package io.softa.starter.message.sms.support.adapter;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.RestClient;

import io.softa.framework.web.resilience.TransientHttpException;
import io.softa.starter.message.shared.ErrorCategory;
import io.softa.starter.message.shared.ErrorClassifier;
import io.softa.starter.message.shared.MessageCategory;
import io.softa.starter.message.sms.dto.SmsAdapterRequest;
import io.softa.starter.message.sms.dto.SmsSendResult;
import io.softa.starter.message.sms.entity.SmsProviderConfig;
import io.softa.starter.message.sms.enums.SmsProvider;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OneWaySmsAdapterTest {

    private static final String SEND_URL = "https://gatewayv5.onewaysms.sg/api.aspx";
    private static final String PASSWORD = "s3cret+pass&x";

    private OneWaySmsAdapter adapter;
    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        adapter = new OneWaySmsAdapter(builder.build());
    }

    private SmsProviderConfig createConfig() {
        SmsProviderConfig config = new SmsProviderConfig();
        config.setId(1L);
        config.setProviderType(SmsProvider.ONEWAYSMS);
        config.setApiKey("api-user");
        config.setApiSecret(PASSWORD);
        config.setSenderId("Softa");
        return config;
    }

    private SmsAdapterRequest request(String phoneNumber, String content) {
        SmsAdapterRequest req = new SmsAdapterRequest();
        req.setPhoneNumber(phoneNumber);
        req.setContent(content);
        return req;
    }

    /** The query as the gateway reads it: names in order, values URL-decoded. */
    private static Map<String, String> params(URI uri) {
        Map<String, String> params = new LinkedHashMap<>();
        for (String pair : uri.getRawQuery().split("&")) {
            int eq = pair.indexOf('=');
            params.put(pair.substring(0, eq), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return params;
    }

    private static RequestMatcher query(Map<String, String> expected) {
        return request -> Assertions.assertEquals(expected, params(request.getURI()));
    }

    private void expectSend(RequestMatcher matcher, String answer) {
        mockServer.expect(requestTo(startsWith(SEND_URL + "?")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(matcher)
                .andRespond(withSuccess(answer, MediaType.TEXT_HTML));
    }

    @Test
    void getProviderReturnsOneWaySms() {
        Assertions.assertEquals(SmsProvider.ONEWAYSMS, adapter.getProvider());
    }

    @Test
    void sendsPlainTextAsLanguageType1() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("apiusername", "api-user");
        expected.put("apipassword", PASSWORD);
        expected.put("mobileno", "6581234567");
        expected.put("senderid", "Softa");
        expected.put("languagetype", "1");
        expected.put("message", "Your code is 123456.\nValid 5 min.");
        expectSend(query(expected), "200806150001\r\n");

        SmsSendResult result = adapter.send(createConfig(),
                request("+6581234567", "Your code is 123456.\nValid 5 min."));

        mockServer.verify();
        Assertions.assertTrue(result.isSuccess());
        Assertions.assertEquals("200806150001", result.getProviderMessageId());
    }

    @Test
    void encodesPlusAndAmpersandSoTheGatewayReadsThemBack() {
        // URI templates leave '+' alone, which an ASP.NET gateway reads as a space.
        expectSend(request -> {
            String raw = request.getURI().getRawQuery();
            Assertions.assertTrue(raw.contains("message=1%2B1%3D2+%26+ok"), raw);
            Assertions.assertTrue(raw.contains("apipassword=s3cret%2Bpass%26x"), raw);
            Assertions.assertEquals("1+1=2 & ok", params(request.getURI()).get("message"));
        }, "1001");

        SmsSendResult result = adapter.send(createConfig(), request("+6581234567", "1+1=2 & ok"));

        mockServer.verify();
        Assertions.assertTrue(result.isSuccess());
    }

    @Test
    void sendsOtherTextAsLanguageType2InHex() {
        expectSend(request -> {
            Map<String, String> p = params(request.getURI());
            Assertions.assertEquals("2", p.get("languagetype"));
            // "softa 测试" — each UTF-16 unit as four upper-case hex digits.
            Assertions.assertEquals("0073006F00660074006100206D4B8BD5", p.get("message"));
        }, "1002");

        SmsSendResult result = adapter.send(createConfig(), request("+6581234567", "softa 测试"));

        mockServer.verify();
        Assertions.assertTrue(result.isSuccess());
    }

    @Test
    void hexWritesASurrogatePairAsTwoUnits() {
        Assertions.assertEquals("0061D83DDE00", OneWaySmsAdapter.toHex("a😀"));
    }

    @Test
    void onlyPrintableAsciiAndLineBreaksArePlainText() {
        Assertions.assertTrue(OneWaySmsAdapter.isPlainText("Hello {name} ~ 100%\r\n"));
        Assertions.assertFalse(OneWaySmsAdapter.isPlainText("Price £5"));
        Assertions.assertFalse(OneWaySmsAdapter.isPlainText("It’s ready"));
        Assertions.assertFalse(OneWaySmsAdapter.isPlainText("tab\there"));
    }

    @Test
    void usesTheConfiguredEndpointAndSendsNoSenderWhenNoneIsSet() {
        SmsProviderConfig config = createConfig();
        config.setApiEndpoint("https://gateway.example.test/");
        config.setSenderId(null);

        mockServer.expect(requestTo(startsWith("https://gateway.example.test/api.aspx?")))
                .andExpect(request -> Assertions.assertFalse(
                        params(request.getURI()).containsKey("senderid")))
                .andRespond(withSuccess("1003", MediaType.TEXT_PLAIN));

        SmsSendResult result = adapter.send(config, request("+6581234567", "Hello"));

        mockServer.verify();
        Assertions.assertTrue(result.isSuccess());
    }

    @Test
    void ignoresTheCategory() {
        expectSend(request -> Assertions.assertEquals(6, params(request.getURI()).size()), "1004");
        SmsAdapterRequest req = request("+6581234567", "Hello");
        req.setCategory(MessageCategory.OTP);

        Assertions.assertTrue(adapter.send(createConfig(), req).isSuccess());
        mockServer.verify();
    }

    @Test
    void negativeResultIsARefusalUnderItsPrefixedCode() {
        expectSend(request -> { }, "-600");

        SmsSendResult result = adapter.send(createConfig(), request("+6581234567", "Hello"));

        Assertions.assertFalse(result.isSuccess());
        Assertions.assertEquals("ONEWAYSMS_-600", result.getErrorCode());
        Assertions.assertTrue(result.getErrorMessage().contains("insufficient credit balance"),
                result.getErrorMessage());
    }

    @Test
    void everyDocumentedRefusalIsClassifiedAndNotRetried() {
        ErrorClassifier classifier = new ErrorClassifier();
        for (long code : new long[]{-100, -200, -300, -400, -500, -600}) {
            SmsSendResult result = adapter.readAnswer(Long.toString(code));
            Assertions.assertEquals("ONEWAYSMS_" + code, result.getErrorCode());
            ErrorCategory category = classifier.classify(result.getErrorCode(), result.getErrorMessage());
            Assertions.assertNotEquals(ErrorCategory.UNKNOWN, category, "code " + code);
            Assertions.assertFalse(category.isRetryable(), "code " + code);
        }
    }

    @Test
    void zeroAndUndocumentedResultsAreRefusals() {
        Assertions.assertEquals("ONEWAYSMS_0", adapter.readAnswer("0").getErrorCode());
        SmsSendResult odd = adapter.readAnswer("-700");
        Assertions.assertFalse(odd.isSuccess());
        Assertions.assertEquals("ONEWAYSMS_-700", odd.getErrorCode());
        Assertions.assertEquals(ErrorCategory.UNKNOWN,
                new ErrorClassifier().classify(odd.getErrorCode(), odd.getErrorMessage()));
    }

    @Test
    void answerThatIsNotANumberMayHaveBeenSent() {
        SmsSendResult result = adapter.readAnswer("<!DOCTYPE html>\n<html><title>Error</title></html>");

        Assertions.assertFalse(result.isSuccess());
        Assertions.assertEquals(ErrorClassifier.OUTCOME_UNKNOWN, result.getErrorCode());
        Assertions.assertTrue(result.getErrorMessage().contains("<!DOCTYPE html> <html>"), result.getErrorMessage());
    }

    @Test
    void httpErrorStatusIsRecordedByStatus() {
        mockServer.expect(requestTo(startsWith(SEND_URL)))
                .andRespond(withServerError().body("<html>500 - Internal server error.</html>"));

        SmsSendResult result = adapter.send(createConfig(), request("+6581234567", "Hello"));

        Assertions.assertFalse(result.isSuccess());
        Assertions.assertEquals("HTTP_500", result.getErrorCode());
        assertNoCredentials(result);
    }

    @Test
    void retryableStatusTheResilientClientGaveUpOnQuotesNoUrl() {
        // The resilience interceptor throws this, quoting the full URL, once its retries are spent.
        mockServer.expect(requestTo(startsWith(SEND_URL)))
                .andRespond(request -> {
                    throw new TransientHttpException(503, "Service Unavailable", request.getURI());
                });

        SmsSendResult result = adapter.send(createConfig(), request("+6581234567", "Your code is 123456"));

        Assertions.assertFalse(result.isSuccess());
        Assertions.assertEquals("HTTP_503", result.getErrorCode());
        assertNoCredentials(result);
    }

    @Test
    void readTimeoutMayHaveBeenSentAndQuotesNoCredentialsOrText() {
        // Spring words it "I/O error on GET request for \"<url>\": Read timed out".
        mockServer.expect(requestTo(startsWith(SEND_URL)))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        SmsSendResult result = adapter.send(createConfig(), request("+6581234567", "Your code is 123456"));

        Assertions.assertFalse(result.isSuccess());
        Assertions.assertEquals(ErrorClassifier.OUTCOME_UNKNOWN, result.getErrorCode());
        Assertions.assertTrue(result.getErrorMessage().contains("Read timed out"), result.getErrorMessage());
        Assertions.assertTrue(result.getErrorMessage().contains(SEND_URL), result.getErrorMessage());
        assertNoCredentials(result);
    }

    @Test
    void refusedConnectionWasNotSentAndQuotesNoCredentials() {
        mockServer.expect(requestTo(startsWith(SEND_URL)))
                .andRespond(withException(new ConnectException("Connection refused")));

        SmsSendResult result = adapter.send(createConfig(), request("+6581234567", "Your code is 123456"));

        Assertions.assertFalse(result.isSuccess());
        Assertions.assertEquals(ErrorClassifier.CONNECT_FAILED, result.getErrorCode());
        assertNoCredentials(result);
    }

    @Test
    void redactCutsTheQueryWhereverTheUrlIsQuoted() {
        String message = "I/O error on GET request for \"" + SEND_URL
                + "?apiusername=u&apipassword=p&message=123456\": Read timed out";
        Assertions.assertEquals("I/O error on GET request for \"" + SEND_URL + "\": Read timed out",
                OneWaySmsAdapter.redact(message));
    }

    private static void assertNoCredentials(SmsSendResult result) {
        String message = result.getErrorMessage();
        Assertions.assertNotNull(message);
        Assertions.assertFalse(message.contains("apipassword"), message);
        Assertions.assertFalse(message.contains("s3cret"), message);
        Assertions.assertFalse(message.contains("123456"), message);
    }
}
