package io.softa.starter.message.sms.support.adapter;

import java.util.TreeMap;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

import io.softa.starter.message.shared.MessageCategory;
import io.softa.starter.message.sms.dto.SmsAdapterRequest;
import io.softa.starter.message.sms.dto.SmsSendResult;
import io.softa.starter.message.sms.entity.SmsProviderConfig;
import io.softa.starter.message.sms.enums.SmsProvider;

/**
 * SMS adapter for Alibaba Cloud Global SMS ({@code SendMessageToGlobe}, version 2018-05-01), shown as
 * "Aliyun Global": Hong Kong, Macao, Taiwan and every country outside the Chinese mainland. It sends the
 * rendered text as it is, so no template binding is needed. It cannot send to the mainland — Aliyun
 * refuses with {@code isv.DOMESTIC_NUMBER_NOT_SUPPORTED}; route CN to an Aliyun Mainland provider.
 * <p>
 * Signed with the V3 algorithm ({@link AliyunAcs3Signer}); every parameter travels in the query.
 * <p>
 * Credential mapping:
 * <ul>
 *   <li>{@code apiKey} → AccessKey ID</li>
 *   <li>{@code apiSecret} → AccessKey Secret</li>
 *   <li>{@code apiEndpoint} → endpoint of the account's region (default Singapore,
 *       {@code https://dysmsapi.ap-southeast-1.aliyuncs.com}; also {@code dysmsapi.eu-central-1.aliyuncs.com},
 *       {@code dysmsapi.ap-southeast-5.aliyuncs.com})</li>
 *   <li>{@code senderNumber} or {@code senderId} → {@code From}, optional: a sender ID of letters and digits,
 *       up to 11 characters with letters or 15 digits; registered where the country requires it
 *       (Singapore: SSIR)</li>
 * </ul>
 * The message's {@link MessageCategory} becomes {@code Type}: OTP → {@code OTP}, Transactional →
 * {@code NOTIFY}, Marketing → {@code MKT}; without one no {@code Type} is sent and Aliyun treats the
 * message as {@code NOTIFY}.
 * <p>
 * Aliyun bills a message once it accepts it, even when the carrier later reports it undelivered.
 *
 * @see <a href="https://api.alibabacloud.com/document/Dysmsapi/2018-05-01/SendMessageToGlobe">SendMessageToGlobe</a>
 */
@Component
public class AliyunGlobalSmsAdapter extends AbstractSmsProviderAdapter {

    static final String DEFAULT_API_ENDPOINT = "https://dysmsapi.ap-southeast-1.aliyuncs.com";
    static final String ACTION = "SendMessageToGlobe";
    static final String VERSION = "2018-05-01";

    public AliyunGlobalSmsAdapter(RestClient smsRestClient) {
        super(smsRestClient);
    }

    @Override
    public SmsProvider getProvider() {
        return SmsProvider.ALIYUN_GLOBAL;
    }

    @Override
    protected SmsSendResult doSend(SmsProviderConfig config, SmsAdapterRequest request) {
        TreeMap<String, String> params = new TreeMap<>();
        params.put("To", stripPlusPrefix(request.getPhoneNumber()));
        params.put("Message", request.getContent());
        String sender = resolveSender(config);
        if (StringUtils.hasText(sender)) {
            params.put("From", stripPlusPrefix(sender));
        }
        String type = typeOf(request.getCategory());
        if (type != null) {
            params.put("Type", type);
        }

        JsonNode json = parseJson(AliyunOpenApi.call(restClient, resolveBaseUrl(config, DEFAULT_API_ENDPOINT),
                ACTION, VERSION, params, config.getApiKey(), config.getApiSecret()));
        if ("OK".equals(json.path("ResponseCode").asString(""))) {
            return SmsSendResult.success(json.path("MessageId").asString(null));
        }
        SmsSendResult failure = AliyunOpenApi.failure(getProvider(), json);
        return failure != null ? failure
                : SmsSendResult.failure(null, getProvider() + " returned an answer without a ResponseCode");
    }

    @Override
    protected SmsSendResult handleHttpError(RestClientResponseException e) {
        return parseErrorBody(e, err -> {
            SmsSendResult failure = AliyunOpenApi.failure(getProvider(), err);
            return failure != null ? failure : defaultHttpError(e);
        });
    }

    /** Aliyun's name for the category; null sends none. */
    static String typeOf(MessageCategory category) {
        if (category == null) {
            return null;
        }
        return switch (category) {
            case OTP -> "OTP";
            case TRANSACTIONAL -> "NOTIFY";
            case MARKETING -> "MKT";
        };
    }
}
