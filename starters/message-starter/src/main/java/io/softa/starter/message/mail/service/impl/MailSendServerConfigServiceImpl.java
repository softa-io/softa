package io.softa.starter.message.mail.service.impl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.softa.starter.message.mail.dto.ConnectivityTestResultDTO;
import io.softa.starter.message.mail.entity.MailSendServerConfig;
import io.softa.starter.message.mail.service.MailSendServerConfigService;
import io.softa.starter.message.mail.support.TenantMailHostGuard;

/**
 * Implementation of {@link MailSendServerConfigService}: a tenant's own send servers. The connectivity
 * test refuses a host inside the deployment.
 */
@Service
public class MailSendServerConfigServiceImpl extends AbstractMailSendServerServiceImpl<MailSendServerConfig>
        implements MailSendServerConfigService {

    @Autowired
    private TenantMailHostGuard hostGuard;

    @Override
    public ConnectivityTestResultDTO testConnectivity(MailSendServerConfig config) {
        hostGuard.requirePublicBeforeConnecting(config.getHost());
        return super.testConnectivity(config);
    }
}
