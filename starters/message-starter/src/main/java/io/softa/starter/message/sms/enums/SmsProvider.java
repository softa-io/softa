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
    @OptionItem(label = "Aliyun Mainland",
            description = "Alibaba Cloud SMS (SendSms): templates approved with Aliyun, for Chinese mainland numbers")
    ALIYUN("Aliyun"),
    @OptionItem(label = "Aliyun Global",
            description = "Alibaba Cloud Global SMS (SendMessageToGlobe): plain text, outside the Chinese mainland")
    ALIYUN_GLOBAL("AliyunGlobal"),
    @OptionItem(description = "Tencent Cloud SMS")
    TENCENT("Tencent"),
    @OptionItem(label = "ClickSend", description = "ClickSend SMS API")
    CLICKSEND("ClickSend"),
    @OptionItem(label = "BytePlus", description = "BytePlus Messaging SMS API")
    BYTEPLUS("BytePlus"),
    @OptionItem(label = "OneWaySMS", description = "OneWaySMS HTTP API (api.aspx)")
    ONEWAYSMS("OneWaySMS");

    @JsonValue
    private final String code;
}
