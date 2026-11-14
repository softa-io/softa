package io.softa.starter.message.mail.service.impl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.softa.starter.message.mail.dto.ConnectivityTestResultDTO;
import io.softa.starter.message.mail.entity.MailReceiveServerConfig;
import io.softa.starter.message.mail.service.MailReceiveServerConfigService;
import io.softa.starter.message.mail.support.TenantMailHostGuard;

/**
 * Implementation of {@link MailReceiveServerConfigService}: a tenant's own receive servers. The
 * connectivity test refuses a host inside the deployment.
 */
@Service
public class MailReceiveServerConfigServiceImpl extends AbstractMailReceiveServerServiceImpl<MailReceiveServerConfig>
        implements MailReceiveServerConfigService {

    @Autowired
    private TenantMailHostGuard hostGuard;

    @Override
    public ConnectivityTestResultDTO testConnectivity(MailReceiveServerConfig config) {
        hostGuard.requirePublicBeforeConnecting(config.getHost());
        return super.testConnectivity(config);
    }
}
