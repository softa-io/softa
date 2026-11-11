package io.softa.starter.message.mail.support;

import org.springframework.stereotype.Component;

/**
 * {@link MailTemplateBodyModeOnChangeHandler}'s editor linkage on the platform's template editor
 * ({@code PlatformMailTemplate.bodyMode}): same fields, same storage contract.
 */
@Component
public class PlatformMailTemplateBodyModeOnChangeHandler extends MailTemplateBodyModeOnChangeHandler {

    @Override
    public String model() {
        return "PlatformMailTemplate";
    }
}
