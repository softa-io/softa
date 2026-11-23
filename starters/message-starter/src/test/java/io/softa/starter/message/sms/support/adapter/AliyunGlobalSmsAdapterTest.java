package io.softa.starter.message.sms.support.adapter;

import java.net.URI;
import java.time.Instant;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import io.softa.starter.message.shared.MessageCategory;
import io.softa.starter.message.sms.dto.SmsAdapterRequest;
import io.softa.starter.message.sms.dto.SmsSendResult;
import io.softa.starter.message.sms.entity.SmsProviderConfig;
import io.softa.starter.message.sms.enums.SmsProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AliyunGlobalSmsAdapterTest {

    private static final String DEFAULT_URL = "https://dysmsapi.ap-southeast-1.aliyuncs.com/";

    private static final String ACCEPTED = """
            {
              "ResponseCode": "OK",
              "NumberDetail": {"Carrier": "SingTel", "Region": "Singapore", "Country": "Singapore"},
              "RequestId": "F655A8D5-B967-440B-8683-DAD6FF8DE990",
              "Segments": "1",
              "ResponseDescription": "The SMS Send Request was accepted",
              "From": "ZingKey",
              "To": "6591234567",
              "MessageId": "1008030300123"
            }
            """;

    private AliyunGlobalSmsAdapter adapter;
    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        adapter = new AliyunGlobalSmsAdapter(builder.build());
    }

    private static SmsProviderConfig config() {
        SmsProviderConfig config = new SmsProviderConfig();
        config.setId(1L);
        config.setProviderType(SmsProvider.ALIYUN_GLOBAL);
        config.setApiKey("AKID-test");
        config.setApiSecret("secret-test");
        config.setSenderId("ZingKey");
        return config;
    }

    private static SmsAdapterRequest request(MessageCategory category) {
        SmsAdapterRequest req = new SmsAdapterRequest();
        req.setPhoneNumber("+6591234567");
        req.setContent("[ZingKey] Your code is 123456");
        req.setCategory(category);
        return req;
    }

    /** The path and query of the request, decoded, as {@code requestTo} cannot match a query it re-encodes. */
    private static RequestMatcher sentTo(String url) {
        return request -> assertThat(request.getURI().getScheme() + "://" + request.getURI().getRawAuthority()
                + request.getURI().getRawPath()).isEqualTo(url);
    }

    @Test
    void getProviderReturnsAliyunGlobal() {
        assertThat(adapter.getProvider()).isEqualTo(SmsProvider.ALIYUN_GLOBAL);
    }

    @Test
    void sendsTheTextInTheQueryAndReturnsTheMessageId() {
        mockServer.expect(sentTo(DEFAULT_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(queryParam("To", "6591234567"))
                .andExpect(queryParam("From", "ZingKey"))
                .andExpect(queryParam("Message", "%5BZingKey%5D%20Your%20code%20is%20123456"))
                .andExpect(queryParam("Type", "OTP"))
                .andExpect(header("x-acs-action", "SendMessageToGlobe"))
                .andExpect(header("x-acs-version", "2018-05-01"))
                .andExpect(header("Authorization", startsWith("ACS3-HMAC-SHA256 Credential=AKID-test,SignedHeaders="
                        + "host;x-acs-action;x-acs-content-sha256;x-acs-date;x-acs-signature-nonce;x-acs-version,"
                        + "Signature=")))
                .andExpect(request -> assertThat(((org.springframework.mock.http.client.MockClientHttpRequest) request)
                        .getBodyAsBytes()).isEmpty())
                .andRespond(withSuccess(ACCEPTED, MediaType.APPLICATION_JSON));

        SmsSendResult result = adapter.send(config(), request(MessageCategory.OTP));

        mockServer.verify();
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getProviderMessageId()).isEqualTo("1008030300123");
    }

    @Test
    void theSignatureIsTheOneTheRequestItselfAccountsFor() {
        mockServer.expect(request -> {
            URI uri = request.getURI();
            TreeMap<String, String> params = new TreeMap<>(
                    UriComponentsBuilder.fromUri(uri).build().getQueryParams().toSingleValueMap());
            params.replaceAll((name, value) -> java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8));
            AliyunAcs3Signer.SignedRequest expected = AliyunAcs3Signer.sign(DEFAULT_URL, "SendMessageToGlobe",
                    "2018-05-01", params, "AKID-test", "secret-test",
                    Instant.parse(request.getHeaders().getFirst("x-acs-date")),
                    request.getHeaders().getFirst("x-acs-signature-nonce"));
            assertThat(uri.getRawQuery()).isEqualTo(expected.uri().getRawQuery());
            assertThat(request.getHeaders().getFirst("Authorization"))
                    .isEqualTo(expected.headers().get("Authorization"));
        }).andRespond(withSuccess(ACCEPTED, MediaType.APPLICATION_JSON));

        assertThat(adapter.send(config(), request(MessageCategory.TRANSACTIONAL)).isSuccess()).isTrue();
        mockServer.verify();
    }

    @Test
    void eachCategoryIsSentInAliyunsWordsAndNoneSendsNoType() {
        assertThat(AliyunGlobalSmsAdapter.typeOf(MessageCategory.OTP)).isEqualTo("OTP");
        assertThat(AliyunGlobalSmsAdapter.typeOf(MessageCategory.TRANSACTIONAL)).isEqualTo("NOTIFY");
        assertThat(AliyunGlobalSmsAdapter.typeOf(MessageCategory.MARKETING)).isEqualTo("MKT");
        assertThat(AliyunGlobalSmsAdapter.typeOf(null)).isNull();

        mockServer.expect(request -> assertThat(request.getURI().getRawQuery()).doesNotContain("Type="))
                .andRespond(withSuccess(ACCEPTED, MediaType.APPLICATION_JSON));
        adapter.send(config(), request(null));
        mockServer.verify();
    }

    @Test
    void noSenderSendsNoFrom_andASenderNumberLosesItsPlus() {
        SmsProviderConfig noSender = config();
        noSender.setSenderId(null);
        mockServer.expect(request -> assertThat(request.getURI().getRawQuery()).doesNotContain("From="))
                .andRespond(withSuccess(ACCEPTED, MediaType.APPLICATION_JSON));
        assertThat(adapter.send(noSender, request(null)).isSuccess()).isTrue();
        mockServer.verify();

        mockServer.reset();
        SmsProviderConfig number = config();
        number.setSenderNumber("+6588880000");
        mockServer.expect(queryParam("From", "6588880000"))
                .andRespond(withSuccess(ACCEPTED, MediaType.APPLICATION_JSON));
        assertThat(adapter.send(number, request(null)).isSuccess()).isTrue();
        mockServer.verify();
    }

    @Test
    void theConfiguredEndpointIsUsed() {
        SmsProviderConfig config = config();
        config.setApiEndpoint("https://dysmsapi.eu-central-1.aliyuncs.com");
        mockServer.expect(sentTo("https://dysmsapi.eu-central-1.aliyuncs.com/"))
                .andRespond(withSuccess(ACCEPTED, MediaType.APPLICATION_JSON));

        assertThat(adapter.send(config, request(null)).isSuccess()).isTrue();
        mockServer.verify();
    }

    @Test
    void aResponseCodeOtherThanOkFailsWithIt() {
        mockServer.expect(requestTo(startsWith(DEFAULT_URL)))
                .andRespond(withSuccess("""
                        {"ResponseCode": "isv.NOT_SUPPORTED_COUNTRY", "ResponseDescription": "Country not supported",
                         "RequestId": "r1"}
                        """, MediaType.APPLICATION_JSON));

        SmsSendResult result = adapter.send(config(), request(null));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("isv.NOT_SUPPORTED_COUNTRY");
        assertThat(result.getErrorMessage()).isEqualTo("Country not supported");
    }

    @Test
    void anOpenApiErrorUnderHttp200FailsWithItsCode() {
        mockServer.expect(requestTo(startsWith(DEFAULT_URL)))
                .andRespond(withSuccess("""
                        {"Code": "ISV.PRODUCT_UN_SUBSCRIPT", "Message": "SMS service not activated.", "RequestId": "r2"}
                        """, MediaType.APPLICATION_JSON));

        SmsSendResult result = adapter.send(config(), request(null));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("ISV.PRODUCT_UN_SUBSCRIPT");
        assertThat(result.getErrorMessage()).isEqualTo("SMS service not activated.");
    }

    @Test
    void anAnswerWithoutACodeFails() {
        mockServer.expect(requestTo(startsWith(DEFAULT_URL)))
                .andRespond(withSuccess("{\"RequestId\": \"r3\"}", MediaType.APPLICATION_JSON));

        SmsSendResult result = adapter.send(config(), request(null));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isNull();
        assertThat(result.getErrorMessage()).contains("without a ResponseCode");
    }

    @Test
    void anHttpErrorIsReadForAliyunsCode() {
        mockServer.expect(requestTo(startsWith(DEFAULT_URL)))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body("""
                        {"Code": "SignatureDoesNotMatch",
                         "Message": "The signature generated by the client does not match the server.",
                         "RequestId": "r4"}
                        """));

        SmsSendResult result = adapter.send(config(), request(null));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("SignatureDoesNotMatch");
    }

    @Test
    void anHttpErrorWithoutAliyunsBodyFallsBackToTheStatus() {
        mockServer.expect(requestTo(startsWith(DEFAULT_URL)))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY).body("Bad Gateway"));

        SmsSendResult result = adapter.send(config(), request(null));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("HTTP_502");
    }
}
