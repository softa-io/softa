package io.softa.starter.seed.dto;

import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The tenant files to run again, and the tenants to run them in.
 */
@Schema(name = "TenantReapplyRequest", description = "The tenant files to run again, and the tenants to run them in")
public record TenantReapplyRequest(

        @Schema(description = "Set-up tenants (Active, Suspended or Closed) to run the files in; required and not "
                + "empty")
        List<Long> tenantIds,

        @Schema(description = "Tenant files to run again, by file name, each of which every one of the tenants is "
                + "due. They run in manifest order whatever order they are named in; required and not empty")
        List<String> fileNames
) {
}
