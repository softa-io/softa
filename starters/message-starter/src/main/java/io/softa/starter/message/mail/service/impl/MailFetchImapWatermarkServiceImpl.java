package io.softa.starter.message.mail.service.impl;

import org.springframework.stereotype.Service;

import io.softa.starter.message.mail.entity.MailFetchImapWatermark;
import io.softa.starter.message.mail.service.MailFetchImapWatermarkService;

/** Implementation of {@link MailFetchImapWatermarkService}: a tenant's receive servers' watermarks. */
@Service
public class MailFetchImapWatermarkServiceImpl extends AbstractMailFetchWatermarkServiceImpl<MailFetchImapWatermark>
        implements MailFetchImapWatermarkService {

    @Override
    protected MailFetchImapWatermark newWatermark() {
        return new MailFetchImapWatermark();
    }
}
