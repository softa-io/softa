package io.softa.framework.web.utils;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Credentials in the request details of an error log line are masked by the name they are sent under, and
 * nothing else in the text changes.
 */
class SecretMaskerTest {

    /** A secret member is masked wherever it sits: at the top, in a nested object, in an object inside an array. */
    @Test
    void masksSecretMembersAtAnyDepth() {
        String json = "{\"rows\":[{\"name\":\"SMTP\",\"password\":\"smtp-pw\"},{\"apiKey\":\"sk-123\"}],"
                + "\"user\":{\"newPassword\":\"N3w-pass!\"},\"ticket\":\"tkt-7f3a\"}";
        assertThat(SecretMasker.maskJson(json)).isEqualTo(
                "{\"rows\":[{\"name\":\"SMTP\",\"password\":\"***\"},{\"apiKey\":\"***\"}],"
                        + "\"user\":{\"newPassword\":\"***\"},\"ticket\":\"***\"}");
    }

    /**
     * Names match whole and ignoring case: {@code password} is masked, {@code passwordValidDay} is not. A business
     * {@code code} is kept; a verification code is masked as {@code verificationCode}.
     */
    @Test
    void matchesWholeNamesIgnoringCase() {
        String json = "{\"Password\":\"x\",\"VerificationCode\":\"482913\",\"code\":\"HR_ADMIN\","
                + "\"employeeCode\":\"E1001\",\"passwordValidDay\":90,\"tokenType\":\"Bearer\",\"codes\":[\"c\"]}";
        assertThat(SecretMasker.maskJson(json)).isEqualTo(
                "{\"Password\":\"***\",\"VerificationCode\":\"***\",\"code\":\"HR_ADMIN\","
                        + "\"employeeCode\":\"E1001\",\"passwordValidDay\":90,\"tokenType\":\"Bearer\",\"codes\":[\"c\"]}");
    }

    /** A code sent as a number is masked too; a null or empty value hides nothing and is kept. */
    @Test
    void masksNumbersAndKeepsAbsentValues() {
        assertThat(SecretMasker.maskJson("{\"verificationCode\" : 482913, \"password\": null, \"ticket\": \"\"}"))
                .isEqualTo("{\"verificationCode\" : \"***\", \"password\": null, \"ticket\": \"\"}");
    }

    /** Text without a secret member, or that is not JSON, comes back as it is. */
    @Test
    void leavesOtherTextAsItIs() {
        String json = "{\"identifier\":\"amy@example.com\",\"tenantCode\":\"acme\"}";
        assertThat(SecretMasker.maskJson(json)).isSameAs(json);
        assertThat(SecretMasker.maskJson("not json: password=x")).isEqualTo("not json: password=x");
        assertThat(SecretMasker.maskJson(null)).isNull();
        assertThat(SecretMasker.maskJson("")).isEmpty();
    }

    /** A body cut off inside a secret value, as a malformed request can be, is still masked. */
    @Test
    void masksATruncatedBody() {
        assertThat(SecretMasker.maskJson("{\"identifier\":\"amy\",\"password\":\"Pa55-wo"))
                .isEqualTo("{\"identifier\":\"amy\",\"password\":\"***\"");
    }

    /** Quotes and colons inside strings do not make a value look like a member, nor hide the member after it. */
    @Test
    void readsStringsWhole() {
        String json = "{\"note\":\"he said \\\"ticket\\\": 1\",\"tags\":[\"x\",\":\"],\"ticket\":\"tkt-7f3a\"}";
        assertThat(SecretMasker.maskJson(json))
                .isEqualTo("{\"note\":\"he said \\\"ticket\\\": 1\",\"tags\":[\"x\",\":\"],\"ticket\":\"***\"}");
    }

    /** A secret query parameter is masked; the others, and a secret one sent empty, are kept. */
    @Test
    void masksSecretQueryParameters() {
        assertThat(SecretMasker.maskQueryString("token=lnk-123&verificationToken=vt-456&documentId=42&ticket="))
                .isEqualTo("token=***&verificationToken=***&documentId=42&ticket=");
        assertThat(SecretMasker.maskQueryString("code=EMP_NO&pageSize=50")).isEqualTo("code=EMP_NO&pageSize=50");
    }
}
