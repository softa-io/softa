package io.softa.starter.message.sms.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Supported SMS provider types.
 */
@Getter
@AllArgsConstructor
public enum SmsProvider {
    TWILIO("Twilio", "Twilio SMS API"),
    INFOBIP("Infobip", "Infobip SMS API"),
    BIRD("Bird", "Bird (MessageBird) SMS API"),
    CM("CM", "CM.com SMS API"),
    SINCH("Sinch", "Sinch SMS API"),
    ALIYUN("Aliyun", "Alibaba Cloud SMS"),
    TENCENT("Tencent", "Tencent Cloud SMS"),
    CUSTOM("Custom", "Custom HTTP SMS provider");

    @JsonValue
    private final String code;
    private final String description;
}
