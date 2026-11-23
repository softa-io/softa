package io.softa.starter.message.sms.support.adapter;

import java.time.Instant;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AliyunAcs3SignerTest {

    /**
     * The worked example of Alibaba Cloud's V3 signature document: the same inputs must give its signature.
     *
     * @see <a href="https://www.alibabacloud.com/help/zh/sdk/product-overview/v3-request-structure-and-signature">V3 signature</a>
     */
    @Test
    void theDocumentedExampleGivesTheDocumentedSignature() {
        TreeMap<String, String> params = new TreeMap<>();
        params.put("RegionId", "cn-shanghai");
        params.put("ImageId", "win2019_1809_x64_dtc_zh-cn_40G_alibase_20230811.vhd");

        AliyunAcs3Signer.SignedRequest signed = AliyunAcs3Signer.sign("https://ecs.cn-shanghai.aliyuncs.com",
                "RunInstances", "2014-05-26", params, "YourAccessKeyId", "YourAccessKeySecret",
                Instant.parse("2023-10-26T10:22:32Z"), "3156853299f313e23d1673dc12e1703d");

        assertThat(signed.headers()).containsEntry("Authorization", "ACS3-HMAC-SHA256 Credential=YourAccessKeyId,"
                + "SignedHeaders=host;x-acs-action;x-acs-content-sha256;x-acs-date;x-acs-signature-nonce;x-acs-version,"
                + "Signature=06563a9e1b43f5dfe96b81484da74bceab24a1d853912eee15083a6f0f3283c0");
        assertThat(signed.headers())
                .containsEntry("x-acs-action", "RunInstances")
                .containsEntry("x-acs-version", "2014-05-26")
                .containsEntry("x-acs-date", "2023-10-26T10:22:32Z")
                .containsEntry("x-acs-signature-nonce", "3156853299f313e23d1673dc12e1703d")
                .containsEntry("x-acs-content-sha256",
                        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
                // The client sets Host from the URI; it is signed, not sent by hand.
                .doesNotContainKey("host");
        assertThat(signed.uri()).hasToString("https://ecs.cn-shanghai.aliyuncs.com/"
                + "?ImageId=win2019_1809_x64_dtc_zh-cn_40G_alibase_20230811.vhd&RegionId=cn-shanghai");
    }

    @Test
    void theQueryIsEncodedAsSignedAndNotAgain() {
        TreeMap<String, String> params = new TreeMap<>();
        params.put("Message", "Your code is 1234 *now* ~ 验证码");
        params.put("To", "6591234567");

        AliyunAcs3Signer.SignedRequest signed = AliyunAcs3Signer.sign("https://dysmsapi.ap-southeast-1.aliyuncs.com",
                "SendMessageToGlobe", "2018-05-01", params, "AK", "SK");

        assertThat(signed.uri().getRawQuery()).isEqualTo(
                "Message=Your%20code%20is%201234%20%2Anow%2A%20~%20%E9%AA%8C%E8%AF%81%E7%A0%81&To=6591234567");
        assertThat(signed.uri().getQuery()).isEqualTo("Message=Your code is 1234 *now* ~ 验证码&To=6591234567");
    }

    @Test
    void anEndpointWithoutSchemeOrWithAPortIsSignedForThatHost() {
        TreeMap<String, String> params = new TreeMap<>();
        params.put("To", "1");
        Instant at = Instant.parse("2026-11-23T00:00:00Z");

        AliyunAcs3Signer.SignedRequest bare = AliyunAcs3Signer.sign("dysmsapi.eu-central-1.aliyuncs.com",
                "A", "V", params, "AK", "SK", at, "n");
        AliyunAcs3Signer.SignedRequest https = AliyunAcs3Signer.sign("https://dysmsapi.eu-central-1.aliyuncs.com/",
                "A", "V", params, "AK", "SK", at, "n");
        AliyunAcs3Signer.SignedRequest withPort = AliyunAcs3Signer.sign("http://localhost:8089",
                "A", "V", params, "AK", "SK", at, "n");

        assertThat(bare.uri()).hasToString("https://dysmsapi.eu-central-1.aliyuncs.com/?To=1");
        assertThat(bare.headers()).isEqualTo(https.headers());
        assertThat(withPort.uri()).hasToString("http://localhost:8089/?To=1");
        assertThat(withPort.headers().get("Authorization")).isNotEqualTo(bare.headers().get("Authorization"));
    }

    @Test
    void eachCallHasItsOwnNonce() {
        TreeMap<String, String> params = new TreeMap<>();
        params.put("To", "1");

        String first = AliyunAcs3Signer.sign("https://h", "A", "V", params, "AK", "SK")
                .headers().get("x-acs-signature-nonce");
        String second = AliyunAcs3Signer.sign("https://h", "A", "V", params, "AK", "SK")
                .headers().get("x-acs-signature-nonce");

        assertThat(first).isNotBlank().isNotEqualTo(second);
    }

    @Test
    void percentEncodeFollowsRfc3986() {
        assertThat(AliyunAcs3Signer.percentEncode(null)).isEmpty();
        assertThat(AliyunAcs3Signer.percentEncode("hello world")).isEqualTo("hello%20world");
        assertThat(AliyunAcs3Signer.percentEncode("a*b")).isEqualTo("a%2Ab");
        assertThat(AliyunAcs3Signer.percentEncode("a~b")).isEqualTo("a~b");
        assertThat(AliyunAcs3Signer.percentEncode("/")).isEqualTo("%2F");
        assertThat(AliyunAcs3Signer.percentEncode("{\"code\":\"1\"}")).isEqualTo("%7B%22code%22%3A%221%22%7D");
    }
}
