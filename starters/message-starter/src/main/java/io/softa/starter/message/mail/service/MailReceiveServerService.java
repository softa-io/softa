package io.softa.starter.message.mail.service;

import java.util.Optional;

import io.softa.starter.message.mail.dto.ConnectivityTestResultDTO;
import io.softa.starter.message.mail.entity.AbstractMailReceiveServer;

/**
 * What the tenant's and the platform's receive server services share. Receiving never crosses owners:
 * each service reads its own owner's table only — {@link MailReceiveServerConfigService} a tenant's,
 * {@link PlatformMailReceiveServerService} the platform's — and mail belongs to the owner of the
 * server it arrived through.
 */
public interface MailReceiveServerService<T extends AbstractMailReceiveServer> extends MailServerService<T> {

    /** The owner's enabled default receive server — the first by {@code sequence} if several are marked. */
    Optional<T> findDefault();

    /** Test connectivity using a transient server object (e.g. before saving). */
    ConnectivityTestResultDTO testConnectivity(T config);
}
