package io.softa.starter.tenant.config;

import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Provisioning-status configuration. {@code expectedSeeders} is the opaque set of seeder keys whose
 * completion the coordinator waits for before flipping a tenant to READY — the app supplies the values;
 * the framework never hardcodes them. Empty (default) = no seed aggregation → {@code beginProvisioning}
 * goes straight to READY (single-tenant / no-MQ deployments, and the zero-behavior-change rollout Step 1).
 */
@Component
@ConfigurationProperties(prefix = "softa.tenant.provisioning")
@Data
public class TenantProvisioningProperties {

    /** Opaque seeder keys required for READY. Order-independent (a set); app-supplied. */
    private List<String> expectedSeeders = List.of();

    /** Alert threshold: a tenant stuck in INITIALIZING beyond this many seconds should be flagged. */
    private long readyTimeoutSeconds = 600;
}
