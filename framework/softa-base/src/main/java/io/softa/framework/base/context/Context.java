package io.softa.framework.base.context;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.Set;
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

    private Long tenantId;

    /**
     * No longer set by the framework. This carried the company selected in a header switcher
     * ({@code X-Company-Id}); the switcher is gone and nothing reads the header any more, so this is
     * always {@code null} on a request. Kept for one release so that application code which clears it
     * (an isolated read that "drops the selection") still compiles; readers must treat it as absent.
     * Which companies a request may see is {@link #grantedCompanyIds}, the grant — a view narrower
     * than the grant does not exist any more.
     *
     * @deprecated always null; use {@link #grantedCompanyIds} for "my companies"
     */
    @Deprecated
    private Long companyId;

    /**
     * ISO 3166-1 alpha-2 country of the company the caller <b>belongs to</b> ({@code EmpInfo.companyId}),
     * resolved server-side by a ContextEnricher. Never read from the client.
     *
     * <p>Only a fallback now. The per-country narrowing reads {@link #grantedCountries} — the
     * countries of the companies the caller's roles reach — and consults this only when that set is
     * unknown or empty: a self-service employee whose roles reach no company still sees their own
     * country's value domains, because they belong to exactly one. For everyone else this is not read.
     */
    private String companyCountry;

    private String token;
    private String traceId;

    private UserInfo userInfo;
    private EmpInfo empInfo;
    /** Caller's system role codes, bridged onto the Context by the enforce
     *  layer (permission-starter interceptor) so framework aspects like
     *  {@code @RequireRole} can gate without depending on the permission model.
     *  The full permission snapshot ({@code PermissionInfo}) lives in
     *  permission-starter and is fetched via the snapshot SPI, not carried here. */
    private Set<String> roleCodes;

    /**
     * The companies the caller may act for — "my companies" — bridged from the permission snapshot
     * ({@code PermissionInfo.grantedCompanyIds}) by the enforce layer, the same way {@link #roleCodes}
     * is, so the ORM can read it without depending on the permission model.
     *
     * <p>Same three states as its source: {@code null} — unrestricted, no company axis configured for
     * this role; empty — no company at all; non-empty — exactly those. This is the whole answer to
     * "which companies": there is no narrower per-request selection any more (see the deprecated
     * {@link #companyId}).
     *
     * <p>Unset on requests that never consult the snapshot — public and authenticated-bypass
     * endpoints, scheduler and MQ threads — where {@code null} therefore means "unknown", which every
     * reader must treat as "do not narrow", never as "narrow to nothing".
     */
    private Set<Long> grantedCompanyIds;

    /**
     * The countries of {@link #grantedCompanyIds} — "my countries" — ISO 3166-1 alpha-2, deduplicated.
     * For an unrestricted grant it is the countries of every company in the tenant, so unlike the id
     * set it is never "all": an SG-only tenant's administrator works in SG, and a value domain seeded
     * for six countries must still narrow to that one. This is what replaces {@link #companyCountry}
     * as the per-country narrowing's input once nothing is selected.
     *
     * <p>{@code null} when unknown (see {@link #grantedCompanyIds}); empty when the caller reaches
     * no company, or none of them carries a country.
     */
    private Set<String> grantedCountries;

    /**
     * Whether to skip permission verification (including model permission and data range),
     * the default is to perform permission verification. A copy keeps it: work handed on from a context
     * running as the system — a per-tenant scope, an async task — runs as the system too. Code that needs
     * the caller's own scope back on a copy sets it to {@code false} there.
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
    private boolean debug;

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
        this.debug = SystemConfig.env != null && SystemConfig.env.isDebug();
    }

    /**
     * @param traceId passed by the client or upstream system
     */
    public Context(String traceId) {
        this.traceId = StringUtils.isBlank(traceId) ? UUID.randomUUID().toString() : traceId;
        this.debug = SystemConfig.env != null && SystemConfig.env.isDebug();
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
        newContext.setTenantId(this.tenantId);
        newContext.setCompanyId(this.companyId);
        newContext.setCompanyCountry(this.companyCountry);
        newContext.setUserInfo(this.userInfo);
        newContext.setEmpInfo(this.empInfo);
        newContext.setRoleCodes(this.roleCodes);
        newContext.setGrantedCompanyIds(this.grantedCompanyIds);
        newContext.setGrantedCountries(this.grantedCountries);
        newContext.setSkipPermissionCheck(this.skipPermissionCheck);
        newContext.setSkipAutoAudit(this.skipAutoAudit);
        newContext.setCrossTenant(this.crossTenant);
        newContext.setDataMask(this.dataMask);
        newContext.setTriggerFlow(this.triggerFlow);
        newContext.setDebug(this.debug);
        newContext.setEffectiveDate(this.effectiveDate);
        return newContext;
    }

}
