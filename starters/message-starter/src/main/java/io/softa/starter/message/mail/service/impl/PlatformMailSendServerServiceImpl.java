package io.softa.starter.message.mail.service.impl;

import org.springframework.stereotype.Service;

import io.softa.starter.message.mail.entity.PlatformMailSendServer;
import io.softa.starter.message.mail.service.PlatformMailSendServerService;

/** Implementation of {@link PlatformMailSendServerService}: one owner, one default. */
@Service
public class PlatformMailSendServerServiceImpl extends AbstractMailSendServerServiceImpl<PlatformMailSendServer>
        implements PlatformMailSendServerService {



}
