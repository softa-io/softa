package io.softa.starter.message.mail.service.impl;

import org.springframework.stereotype.Service;

import io.softa.starter.message.mail.entity.PlatformMailTemplate;
import io.softa.starter.message.mail.service.PlatformMailTemplateService;

/** Implementation of {@link PlatformMailTemplateService}. */
@Service
public class PlatformMailTemplateServiceImpl extends AbstractMailTemplateServiceImpl<PlatformMailTemplate>
        implements PlatformMailTemplateService {
}
