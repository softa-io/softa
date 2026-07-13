package io.softa.starter.flow.runtime.engine;

import java.time.LocalDateTime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.softa.starter.flow.service.FlowDelegationService;

/**
 * Periodically expires delegation rules that have passed their end time.
 */
@Slf4j
@Component
public class DelegationExpiryScheduler {

    @Autowired(required = false)
    private FlowDelegationService delegationService;

    /**
     * Expire delegation rules that have passed their end time.
     * Triggered externally via cron-starter (flow_delegation_expiry).
     */
    public void expireDelegations() {
        if (delegationService == null) {
            return;
        }
        int expiredCount = delegationService.expireDelegations(LocalDateTime.now());
        if (expiredCount > 0) {
            log.info("Expired {} delegation rule(s)", expiredCount);
        }
    }
}

