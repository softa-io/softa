package io.softa.framework.base.context;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.enums.Language;
import io.softa.framework.base.enums.Timezone;

/**
 * Environment parameters of current user.
 */
@Data
public class Context implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private Long userId;
    private String name;

    private Language language = BaseConstant.DEFAULT_LANGUAGE;
    private Timezone timezone;

    private Long companyId;
    private Long tenantId;

    private String token;
    private String traceId;

    /**
     * Business correlation ID, injected by the caller (e.g., workItemId).
     * Propagated through Context and stored in ChangeLog for WorkItem-centric change tracking.
     */
    private String correlationId;

    private UserInfo userInfo;
    private EmpInfo empInfo;
    private PermissionInfo permissionInfo;

    /**
     * Whether to skip permission verification (including model permission and data range),
     * the default is to perform permission verification.
     */
    private boolean skipPermissionCheck = false;

    private boolean skipAutoAudit = false;

    /**
     * Whether to skip tenant isolation.
     * When true, ORM treats all models as non-multi-tenant:
     * no tenant_id filtering on reads, no auto-fill on writes.
     */
    private boolean crossTenant = false;

    /**
     * Whether to mask field value which maskingType is not null
     */
    private boolean dataMask = false;

    /**
     * Whether to trigger the flow, the default is true.
     * It is allowed to be set to not trigger in specific scenarios,
     * such as batch import and custom Controller, and manually trigger it.
     */
    private boolean triggerFlow = true;

    /**
     * Set by API parameters or @Debug annotation, used to output Debug logs,
     */
    private boolean debug = false;

    /**
     * The effective date specified when querying timeline data, the default is the current date,
     * and can be explicitly passed in the API parameters.
     */
    private LocalDate effectiveDate = LocalDate.now();

    /**
     * Default constructor, use UUID to fill in when traceId is not specified,
     * used for scenarios such as cron tasks and integration
     */
    public Context() {
        this.traceId = UUID.randomUUID().toString();
        this.debug = SystemConfig.env.isDebug();
    }

    /**
     * @param traceId passed by the client or upstream system
     */
    public Context(String traceId) {
        this.traceId = StringUtils.isBlank(traceId) ? UUID.randomUUID().toString() : traceId;
        this.debug = SystemConfig.env.isDebug();
    }

    public void setEffectiveDate(LocalDate effectiveDate) {
        if (effectiveDate != null) {
            this.effectiveDate = effectiveDate;
        }
    }

    /**
     * Set the language for current user.
     * Keep the default language if the language parameter is null.
     *
     * @param language the language to set
     */
    public void setLanguage(Language language) {
        if (language != null) {
            this.language = language;
        }
    }

    public Context copy() {
        Context newContext = new Context(this.traceId);
        newContext.setUserId(this.userId);
        newContext.setName(this.name);
        newContext.setLanguage(this.language);
        newContext.setTimezone(this.timezone);
        newContext.setCompanyId(this.companyId);
        newContext.setTenantId(this.tenantId);
        newContext.setUserInfo(this.userInfo);
        newContext.setEmpInfo(this.empInfo);
        newContext.setSkipAutoAudit(this.skipAutoAudit);
        newContext.setCrossTenant(this.crossTenant);
        newContext.setDataMask(this.dataMask);
        newContext.setTriggerFlow(this.triggerFlow);
        newContext.setDebug(this.debug);
        newContext.setEffectiveDate(this.effectiveDate);
        newContext.setCorrelationId(this.correlationId);
        return newContext;
    }

}
