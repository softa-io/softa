package io.softa.starter.message.shared;

import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Maps provider error codes / messages onto an {@link ErrorCategory}.
 * <p>
 * Implementation is intentionally defensive: when in doubt, return
 * {@link ErrorCategory#UNKNOWN} so retry keeps running. Specific provider
 * code tables are folded in here without changing callers: SMTP reply codes,
 * HTTP statuses ({@code HTTP_<status>}), Infobip's status names and Aliyun's error codes so far.
 */
@Component
public class ErrorClassifier {

    /** No server or provider resolves, or the one the record names is gone. */
    public static final String CONFIG_NOT_RESOLVABLE = "CONFIG_NOT_RESOLVABLE";

    /** The template the request names does not exist, is disabled, or fails to render. */
    public static final String TEMPLATE_NOT_RESOLVABLE = "TEMPLATE_NOT_RESOLVABLE";

    /** The monthly quota of the bucket the send is charged to is used up. */
    public static final String MONTHLY_QUOTA_EXCEEDED = "MONTHLY_QUOTA_EXCEEDED";

    /** The bucket the send is charged to has reached today's cap; it starts again tomorrow. */
    public static final String DAILY_QUOTA_EXCEEDED = "DAILY_QUOTA_EXCEEDED";

    /** The prepared content cannot go out: empty, over the size cap, or a malformed Reply-To. */
    public static final String CONTENT_INVALID = "CONTENT_INVALID";

    /** Prefix of the code an SMS adapter gives an HTTP error response: {@code HTTP_401}. */
    public static final String HTTP_PREFIX = "HTTP_";

    /**
     * Infobip message statuses an SMS send can be refused with at submission, by name
     * (<a href="https://www.infobip.com/docs/developer-resources/api-fundamentals/message-status-codes">status codes</a>).
     * Only the platform's own failures heal by retrying; the rest need the account, the sender,
     * the number or the content fixed first.
     */
    private static final Map<String, ErrorCategory> INFOBIP_STATUSES = Map.ofEntries(
            // The account cannot send this: sender not registered, no credit, no route, test-only account.
            Map.entry("REJECTED_SOURCE", ErrorCategory.CONFIG),
            Map.entry("REJECTED_NOT_ENOUGH_CREDITS", ErrorCategory.CONFIG),
            Map.entry("REJECTED_PREPAID_PACKAGE_EXPIRED", ErrorCategory.CONFIG),
            Map.entry("REJECTED_ROUTE_NOT_AVAILABLE", ErrorCategory.CONFIG),
            Map.entry("REJECTED_NETWORK", ErrorCategory.CONFIG),
            Map.entry("REJECTED_DESTINATION_NOT_REGISTERED", ErrorCategory.CONFIG),
            // The number or the content is wrong.
            Map.entry("REJECTED_PREFIX_MISSING", ErrorCategory.INVALID_INPUT),
            Map.entry("REJECTED_DESTINATION", ErrorCategory.INVALID_INPUT),
            Map.entry("MISSING_TO", ErrorCategory.INVALID_INPUT),
            Map.entry("REJECTED_MESSAGE_TOO_LONG", ErrorCategory.INVALID_INPUT),
            Map.entry("REJECTED_INVALID_UDH", ErrorCategory.INVALID_INPUT),
            // The recipient or the sender is blocked: do-not-disturb, blocklists, flooding, anti-fraud.
            Map.entry("REJECTED_DND", ErrorCategory.PERMANENT),
            Map.entry("REJECTED_DESTINATION_BLOCKLISTED", ErrorCategory.PERMANENT),
            Map.entry("REJECTED_SENDER", ErrorCategory.PERMANENT),
            Map.entry("REJECTED_FLOODING_FILTER", ErrorCategory.PERMANENT),
            Map.entry("SIGNALS_BLOCKED", ErrorCategory.PERMANENT),
            // Infobip's own platform failed; its docs say to submit again.
            Map.entry("REJECTED_SYSTEM_ERROR", ErrorCategory.TRANSIENT),
            Map.entry("UNDELIVERABLE_REJECTED_PLATFORM", ErrorCategory.TRANSIENT));

    /**
     * Alibaba Cloud error codes the two Aliyun SMS adapters can be refused with when sending
     * (<a href="https://api.alibabacloud.com/document/Dysmsapi/2018-05-01/errorCode">SMS error codes</a>,
     * plus the gateway's own {@code InvalidAccessKeyId.*} and {@code Throttling.User}). Read before the
     * keyword heuristics, which misfile several of them: a blocked keyword
     * ({@code isv.BLACK_KEY_CONTROL_LIMIT}) or an over-long variable ({@code isv.PARAM_LENGTH_LIMIT}) is not a
     * rate limit, and an unknown AccessKey is not bad input. The codes of the signature, template,
     * qualification and short-URL management operations are left out.
     */
    private static final Map<String, ErrorCategory> ALIYUN_CODES = Map.ofEntries(
            // The credentials or the RAM user's rights.
            Map.entry("SignatureDoesNotMatch", ErrorCategory.AUTH),
            Map.entry("InvalidAccessKeyId.NotFound", ErrorCategory.AUTH),
            Map.entry("InvalidAccessKeyId.Inactive", ErrorCategory.AUTH),
            Map.entry("isp.RAM_PERMISSION_DENY", ErrorCategory.AUTH),
            Map.entry("Forbidden.Operation", ErrorCategory.AUTH),
            Map.entry("ForbiddenAction", ErrorCategory.AUTH),
            Map.entry("ISV.Forbidden", ErrorCategory.AUTH),
            // The account: state, balance, service not activated, where it may send from and to.
            Map.entry("Account.Abnormal", ErrorCategory.CONFIG),
            Map.entry("isv.ACCOUNT_ABNORMAL", ErrorCategory.CONFIG),
            Map.entry("isv.ACCOUNT_NOT_EXISTS", ErrorCategory.CONFIG),
            Map.entry("isv.SECURITY_FROZEN_ACCOUNT", ErrorCategory.CONFIG),
            Map.entry("CustNotExistError", ErrorCategory.CONFIG),
            Map.entry("GrayCustAccessError", ErrorCategory.CONFIG),
            Map.entry("isv.DENY_IP_RANGE", ErrorCategory.CONFIG),
            Map.entry("Amount.NotEnough", ErrorCategory.CONFIG),
            Map.entry("isv.AMOUNT_NOT_ENOUGH", ErrorCategory.CONFIG),
            Map.entry("isv.OUT_OF_SERVICE", ErrorCategory.CONFIG),
            Map.entry("isv.PRODUCT_UN_SUBSCRIPT", ErrorCategory.CONFIG),
            Map.entry("ISV.PRODUCT_UN_SUBSCRIPT", ErrorCategory.CONFIG),
            Map.entry("isv.PRODUCT_UNSUBSCRIBE", ErrorCategory.CONFIG),
            Map.entry("ServiceNotOpened", ErrorCategory.CONFIG),
            Map.entry("isv.NOT_SUPPORTED_COUNTRY", ErrorCategory.CONFIG),
            Map.entry("isv.DOMESTIC_NUMBER_NOT_SUPPORTED", ErrorCategory.CONFIG),
            Map.entry("InvalidParameter.From", ErrorCategory.CONFIG),
            Map.entry("InvalidParameter.SenderId", ErrorCategory.CONFIG),
            // Aliyun Mainland: the binding's signature or template, or a test signature's limits.
            Map.entry("isv.SMS_SIGNATURE_ILLEGAL", ErrorCategory.CONFIG),
            Map.entry("isv.SMS_SIGN_ILLEGAL", ErrorCategory.CONFIG),
            Map.entry("isv.SIGN_NAME_ILLEGAL", ErrorCategory.CONFIG),
            Map.entry("isv.SMS_TEMPLATE_ILLEGAL", ErrorCategory.CONFIG),
            Map.entry("isv.SMS_SIGNATURE_SCENE_ILLEGAL", ErrorCategory.CONFIG),
            Map.entry("isv.TEMPLATE_PARAMS_ILLEGAL", ErrorCategory.CONFIG),
            Map.entry("isv.TEMPLATE_MISSING_PARAMETERS", ErrorCategory.CONFIG),
            Map.entry("isv.SMS_TEST_NUMBER_LIMIT", ErrorCategory.CONFIG),
            Map.entry("isv.SMS_TEST_SIGN_TEMPLATE_LIMIT", ErrorCategory.CONFIG),
            Map.entry("isv.SMS_TEST_TEMPLATE_PARAMS_ILLEGAL", ErrorCategory.CONFIG),
            // Rate limits: per number (BUSINESS_LIMIT_CONTROL), per day, per second.
            Map.entry("isv.BUSINESS_LIMIT_CONTROL", ErrorCategory.QUOTA),
            Map.entry("isv.DAY_LIMIT_CONTROL", ErrorCategory.QUOTA),
            Map.entry("DayLimitControl", ErrorCategory.QUOTA),
            Map.entry("Throttling.User", ErrorCategory.QUOTA),
            // The number or the content.
            Map.entry("InvalidParameter.To", ErrorCategory.INVALID_INPUT),
            Map.entry("InvalidParam.PhoneNumber", ErrorCategory.INVALID_INPUT),
            Map.entry("InvalidParameter.phoneNumber", ErrorCategory.INVALID_INPUT),
            Map.entry("isv.MOBILE_NUMBER_ILLEGAL", ErrorCategory.INVALID_INPUT),
            Map.entry("MissingParameter.To", ErrorCategory.INVALID_INPUT),
            Map.entry("MissingParameter.Message", ErrorCategory.INVALID_INPUT),
            Map.entry("isv.SMS_CONTENT_ILLEGAL", ErrorCategory.INVALID_INPUT),
            Map.entry("FILTER", ErrorCategory.INVALID_INPUT),
            Map.entry("isv.BLACK_KEY_CONTROL_LIMIT", ErrorCategory.INVALID_INPUT),
            Map.entry("isv.PARAM_LENGTH_LIMIT", ErrorCategory.INVALID_INPUT),
            Map.entry("isv.PARAM_NOT_SUPPORT_URL", ErrorCategory.INVALID_INPUT),
            Map.entry("InvalidParameter.Type", ErrorCategory.INVALID_INPUT),
            Map.entry("isv.INVALID_PARAMETERS", ErrorCategory.INVALID_INPUT),
            Map.entry("INVALID_PARAMETERS", ErrorCategory.INVALID_INPUT),
            Map.entry("isv.INVALID_JSON_PARAM", ErrorCategory.INVALID_INPUT),
            // The recipient unsubscribed from promotional messages.
            Map.entry("isv.CUSTOMER_REFUSED", ErrorCategory.PERMANENT),
            // Aliyun's own failure, or a stale timestamp / reused nonce a new attempt signs afresh.
            Map.entry("isp.SYSTEM_ERROR", ErrorCategory.TRANSIENT),
            Map.entry("isp.GATEWAY_ERROR", ErrorCategory.TRANSIENT),
            Map.entry("InvalidTimeStamp.Expired", ErrorCategory.TRANSIENT),
            Map.entry("SignatureNonceUsed", ErrorCategory.TRANSIENT));

    public ErrorCategory classify(String errorCode, String errorMessage) {
        if (errorCode != null) {
            ErrorCategory c = classifyByCode(errorCode.trim());
            if (c != null) return c;
        }
        if (errorMessage != null) {
            ErrorCategory c = classifyByMessage(errorMessage.toLowerCase(Locale.ROOT));
            if (c != null) return c;
        }
        return ErrorCategory.UNKNOWN;
    }

    private ErrorCategory classifyByCode(String code) {
        // Our own marker codes, set when a send is prepared or its config resolved. Checked
        // before the heuristics below: the quota codes must not read as a retryable QUOTA.
        if (code.equals(CONFIG_NOT_RESOLVABLE)
                || code.equals(TEMPLATE_NOT_RESOLVABLE)
                || code.equals(MONTHLY_QUOTA_EXCEEDED)
                || code.equals(DAILY_QUOTA_EXCEEDED)) {
            return ErrorCategory.CONFIG;
        }
        if (code.equals(CONTENT_INVALID)) {
            return ErrorCategory.INVALID_INPUT;
        }
        // An HTTP error response no adapter could read a provider code from: "HTTP_<status>".
        if (code.startsWith(HTTP_PREFIX)) {
            ErrorCategory byStatus = classifyHttpStatus(code.substring(HTTP_PREFIX.length()));
            if (byStatus != null) return byStatus;
        }
        ErrorCategory infobip = INFOBIP_STATUSES.get(code);
        if (infobip != null) {
            return infobip;
        }
        ErrorCategory aliyun = ALIYUN_CODES.get(code);
        if (aliyun != null) {
            return aliyun;
        }
        // SMTP reply codes from SmtpMailTransport: "SMTP_<ExceptionType>".
        if (code.startsWith("SMTP_")) {
            return classifySmtpExceptionType(code.substring(5));
        }
        // 5xx SMTP reply or Twilio permanent.
        if (code.startsWith("550") || code.startsWith("551") || code.startsWith("553")) {
            return ErrorCategory.PERMANENT;
        }
        if (code.startsWith("552") /* message too large */) {
            return ErrorCategory.INVALID_INPUT;
        }
        if (code.startsWith("421") /* server not available */
                || code.startsWith("450") || code.startsWith("451") || code.startsWith("452")) {
            return ErrorCategory.TRANSIENT;
        }
        if (code.startsWith("535") /* auth failed */
                || code.startsWith("530")) {
            return ErrorCategory.AUTH;
        }
        // Aliyun / Tencent bizCode heuristics — rate limits usually contain "LIMIT".
        String upper = code.toUpperCase(Locale.ROOT);
        if (upper.contains("LIMIT") || upper.contains("QUOTA") || upper.contains("THROTTL")) {
            return ErrorCategory.QUOTA;
        }
        if (upper.contains("AUTH") || upper.contains("SIGNATURE") || upper.contains("FORBIDDEN")) {
            return ErrorCategory.AUTH;
        }
        if (upper.contains("INVALID") || upper.contains("MALFORM") || upper.contains("PARAM")) {
            return ErrorCategory.INVALID_INPUT;
        }
        if (upper.contains("TIMEOUT") || upper.contains("UNAVAILABLE") || upper.contains("RETRY")) {
            return ErrorCategory.TRANSIENT;
        }
        return null;
    }

    /**
     * {@code SmtpMailTransport} codes a failure {@code SMTP_<reply code>} when the server refused with
     * one, else {@code SMTP_<exception type>}.
     */
    private ErrorCategory classifySmtpExceptionType(String exceptionType) {
        if (exceptionType.length() == 3 && exceptionType.chars().allMatch(Character::isDigit)) {
            return classifySmtpReply(Integer.parseInt(exceptionType));
        }
        return switch (exceptionType) {
            case "AuthenticationFailedException", "MailAuthenticationException" -> ErrorCategory.AUTH;
            case "SendFailedException" -> ErrorCategory.PERMANENT;
            case "AddressException", "MailParseException", "MailPreparationException" -> ErrorCategory.INVALID_INPUT;
            case "MessagingException", "MailSendException", "MailException" -> ErrorCategory.TRANSIENT;
            default -> ErrorCategory.UNKNOWN;
        };
    }

    /**
     * An SMTP reply (RFC 5321): 4xx is a temporary refusal, retried; 5xx is permanent, except that 552
     * (message too large) is the content's fault and 530 / 534 / 535 / 538 the credentials'.
     */
    private static ErrorCategory classifySmtpReply(int reply) {
        if (reply >= 400 && reply < 500) {
            return ErrorCategory.TRANSIENT;
        }
        return switch (reply) {
            case 530, 534, 535, 538 -> ErrorCategory.AUTH;
            case 552 -> ErrorCategory.INVALID_INPUT;
            default -> reply >= 500 && reply < 600 ? ErrorCategory.PERMANENT : ErrorCategory.UNKNOWN;
        };
    }

    /**
     * An HTTP status: credentials refused (401 / 403), rate-limited (429), a request the provider
     * will never accept (400 / 404 / 413 / 422), or the provider failing (408, 5xx). Any other
     * status falls through to the heuristics.
     */
    private static ErrorCategory classifyHttpStatus(String status) {
        if (status.length() != 3 || !status.chars().allMatch(Character::isDigit)) {
            return null;
        }
        int s = Integer.parseInt(status);
        return switch (s) {
            case 401, 403 -> ErrorCategory.AUTH;
            case 429 -> ErrorCategory.QUOTA;
            case 400, 404, 413, 422 -> ErrorCategory.INVALID_INPUT;
            case 408 -> ErrorCategory.TRANSIENT;
            default -> s >= 500 && s < 600 ? ErrorCategory.TRANSIENT : null;
        };
    }

    private ErrorCategory classifyByMessage(String msg) {
        if (msg.contains("timeout") || msg.contains("timed out") || msg.contains("connection reset")
                || msg.contains("unavailable") || msg.contains("try again")) {
            return ErrorCategory.TRANSIENT;
        }
        if (msg.contains("rate limit") || msg.contains("too many requests")
                || msg.contains("quota") || msg.contains("throttle")) {
            return ErrorCategory.QUOTA;
        }
        if (msg.contains("unauthorized") || msg.contains("forbidden")
                || msg.contains("invalid credentials") || msg.contains("authentication failed")) {
            return ErrorCategory.AUTH;
        }
        if (msg.contains("invalid address") || msg.contains("malformed")
                || msg.contains("bad recipient") || msg.contains("no recipient")) {
            return ErrorCategory.INVALID_INPUT;
        }
        if (msg.contains("no such user") || msg.contains("user unknown")
                || msg.contains("does not exist")) {
            return ErrorCategory.PERMANENT;
        }
        return null;
    }
}
