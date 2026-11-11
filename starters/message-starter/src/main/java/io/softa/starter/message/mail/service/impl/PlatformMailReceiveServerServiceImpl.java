package io.softa.starter.message.mail.service.impl;

import org.springframework.stereotype.Service;

import io.softa.starter.message.mail.entity.PlatformMailReceiveServer;
import io.softa.starter.message.mail.service.PlatformMailReceiveServerService;

/** Implementation of {@link PlatformMailReceiveServerService}. */
@Service
public class PlatformMailReceiveServerServiceImpl extends AbstractMailReceiveServerServiceImpl<PlatformMailReceiveServer>
        implements PlatformMailReceiveServerService {
}
