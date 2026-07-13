package io.softa.starter.message.sms.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import io.softa.framework.base.annotation.OptionItem;
import io.softa.framework.base.annotation.OptionSet;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Supported SMS provider types.
 */
@Getter
@AllArgsConstructor
@OptionSet(label = "SMS Provider")
public enum SmsProvider {
    @OptionItem(description = "Twilio SMS API")
    TWILIO("Twilio"),
    @OptionItem(description = "Infobip SMS API")
    INFOBIP("Infobip"),
    @OptionItem(description = "Bird (MessageBird) SMS API")
    BIRD("Bird"),
    @OptionItem(label = "CM", description = "CM.com SMS API")
    CM("CM"),
    @OptionItem(description = "Sinch SMS API")
    SINCH("Sinch"),
    @OptionItem(description = "Alibaba Cloud SMS")
    ALIYUN("Aliyun"),
    @OptionItem(description = "Tencent Cloud SMS")
    TENCENT("Tencent");

    @JsonValue
    private final String code;
}
