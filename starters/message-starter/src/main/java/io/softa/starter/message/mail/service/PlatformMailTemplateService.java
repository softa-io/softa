package io.softa.starter.message.mail.service;

import io.softa.starter.message.mail.entity.PlatformMailTemplate;

/**
 * CRUD and resolution service for the platform's own {@link PlatformMailTemplate}s — what a
 * {@code PLATFORM}-scope send renders. Its codes are the platform codes no tenant template may take.
 */
public interface PlatformMailTemplateService extends MailTemplateTierService<PlatformMailTemplate> {
}
