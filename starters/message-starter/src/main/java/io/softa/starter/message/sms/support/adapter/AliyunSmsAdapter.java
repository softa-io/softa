package io.softa.starter.message.sms.support.adapter;

import java.util.TreeMap;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

import io.softa.framework.base.utils.JsonUtils;
import io.softa.starter.message.sms.dto.SmsAdapterRequest;
import io.softa.starter.message.sms.dto.SmsSendResult;
import io.softa.starter.message.sms.entity.SmsProviderConfig;
import io.softa.starter.message.sms.enums.SmsProvider;

/**
 * SMS adapter for Alibaba Cloud SMS in the Chinese mainland ({@code SendSms}, version 2017-05-25),
 * shown as "Aliyun Mainland". It sends a template approved with Aliyun, filled with the variables by
 * name, so a softa template needs a provider binding naming the Aliyun template code and signature.
 * Numbers outside the mainland go to {@link AliyunGlobalSmsAdapter}.
 * <p>
 * Signed with the V3 algorithm ({@link AliyunAcs3Signer}); every parameter travels in the query.
 * <p>
 * Credential mapping:
 * <ul>
 *   <li>{@code apiKey} → AccessKey ID</li>
 *   <li>{@code apiSecret} → AccessKey Secret</li>
 *   <li>{@code apiEndpoint} → endpoint (default {@code https://dysmsapi.aliyuncs.com}); the region is the
 *       endpoint's, {@code extConfig} is not read</li>
 *   <li>{@code signName} → {@code SignName} (required by Aliyun)</li>
 *   <li>{@code externalTemplateId} → {@code TemplateCode} (required by Aliyun)</li>
 *   <li>{@code templateVariables} → {@code TemplateParam}, as a JSON object</li>
 * </ul>
 *
 * @see <a href="https://api.alibabacloud.com/document/Dysmsapi/2017-05-25/SendSms">SendSms</a>
 */
@Component
public class AliyunSmsAdapter extends AbstractSmsProviderAdapter {

    static final String DEFAULT_API_ENDPOINT = "https://dysmsapi.aliyuncs.com";
    static final String ACTION = "SendSms";
    static final String VERSION = "2017-05-25";

    public AliyunSmsAdapter(RestClient smsRestClient) {
        super(smsRestClient);
    }

    @Override
    public SmsProvider getProvider() {
        return SmsProvider.ALIYUN;
    }

    @Override
    protected SmsSendResult doSend(SmsProviderConfig config, SmsAdapterRequest request) {
        if (!StringUtils.hasText(request.getExternalTemplateId())) {
            return SmsSendResult.failure(null, "Aliyun SMS requires externalTemplateId (TemplateCode)");
        }
        TreeMap<String, String> params = new TreeMap<>();
        params.put("PhoneNumbers", stripPlusPrefix(request.getPhoneNumber()));
        params.put("SignName", request.getSignName() != null ? request.getSignName() : "");
        params.put("TemplateCode", request.getExternalTemplateId());
        if (request.getTemplateVariables() != null && !request.getTemplateVariables().isEmpty()) {
            params.put("TemplateParam", JsonUtils.objectToString(request.getTemplateVariables()));
        }

        JsonNode json = parseJson(AliyunOpenApi.call(restClient, resolveBaseUrl(config, DEFAULT_API_ENDPOINT),
                ACTION, VERSION, params, config.getApiKey(), config.getApiSecret()));
        if ("OK".equals(json.path("Code").asString(""))) {
            return SmsSendResult.success(json.path("BizId").asString(null));
        }
        SmsSendResult failure = AliyunOpenApi.failure(getProvider(), json);
        return failure != null ? failure
                : SmsSendResult.failure(null, getProvider() + " returned an answer without a Code");
    }

    @Override
    protected SmsSendResult handleHttpError(RestClientResponseException e) {
        return parseErrorBody(e, err -> {
            SmsSendResult failure = AliyunOpenApi.failure(getProvider(), err);
            return failure != null ? failure : defaultHttpError(e);
        });
    }
}
