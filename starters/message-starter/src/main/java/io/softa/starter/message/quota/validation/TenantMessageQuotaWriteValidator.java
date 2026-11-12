package io.softa.starter.message.quota.validation;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.service.validation.ModelWriteValidator;
import io.softa.starter.message.shared.TenantScopes;

/**
 * Message quotas are configured by platform operations only. Checked at the three write roots, so every
 * path is covered — single rows and batches, the generic {@code /TenantMessageQuota/...} endpoints the
 * admin pages call, imports, programmatic writes — and a tenant session cannot raise its own ceiling,
 * whatever its role grants. The model is not tenant-isolated, so nothing else would stop it.
 */
@Component
@Order(100)
public class TenantMessageQuotaWriteValidator implements ModelWriteValidator {

    static final String MODEL = "TenantMessageQuota";

    private static final String WHAT = "Message quotas";

    @Override
    public boolean supports(String modelName) {
        return MODEL.equals(modelName);
    }

    @Override
    public void validateBatch(String modelName, List<Map<String, Object>> rows, AccessType accessType,
                              Map<String, Object> scratch) {
        TenantScopes.requirePlatformCaller(WHAT);
    }

    @Override
    public void validateDelete(String modelName, List<? extends Serializable> ids) {
        TenantScopes.requirePlatformCaller(WHAT);
    }
}
