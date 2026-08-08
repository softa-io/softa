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
     * The company this request is being made under — "which company am I looking at", chosen in the
     * header switcher and carried on {@code X-Company-Id}. What the per-company and per-country
     * narrowing read.
     *
     * <p>Not the company the caller belongs to. That one lives on {@code EmpInfo.companyId} and
     * anchors permission rules ({@code USER_COMP_ID}); it answers "whose records are these", while
     * this answers "which company's books am I in right now". A role may reach several companies and
     * switch between them, so a rule anchored on this field would widen with every switch — which is
     * why the two are kept apart rather than merged. The nesting is the reminder:
     * {@code context.getCompanyId()} is the selection, {@code context.getEmpInfo().getCompanyId()}
     * is the affiliation.
     *
     * <p>The HR app calls this a legal entity; the framework says company throughout, the same
     * translation {@code USER_COMP_ID} already makes.
     *
     * <p>Never cached in the session: a cached value defeats switching, and multiple browser tabs
     * would overwrite each other.
     */
    private Long companyId;

    /**
     * ISO 3166-1 alpha-2 country of {@link #companyId}, resolved server-side by a
     * ContextEnricher (the app supplies it, since only the app knows what a company row is).
     * Never read from the client — a forged value would bypass per-country narrowing.
     *
     * <p>May be set while {@link #companyId} is null: a caller with no company to select — a role
     * granted no company, which is what a self-service employee is — falls back to the country of the
     * company it belongs to, so that per-country value domains still narrow. The reverse asymmetry
     * also exists and predates it (a selected company whose row carries no country). So this field
     * answers "which country's data applies to this request", not "the selected company's country";
     * the {@code SELECTED_COMP_COUNTRY} placeholder answers the latter and is guarded accordingly.
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
     * Whether to skip permission verification (including model permission and data range),
     * the default is to perform permission verification.
     */
    private boolean skipPermissionCheck = false;

    /**
     * Narrow bypass: skip ONLY row-scope enforcement (scope filters and id-range
     * checks). Field-level guards — sensitive-field masking and write-payload
     * checks — stay active, which is what separates this from
     * {@link #skipPermissionCheck}: that one turns off ALL permission layers.
     *
     * <p>Intended pattern (custom-endpoint main-model scope): verify the caller's
     * data scope on the endpoint's main model FIRST, and only then set this flag
     * for the rest of the call, so the endpoint's internal cross-model reads and
     * writes are not silently emptied / blocked by row scope they were never
     * granted. Enable only AFTER the entry check has passed — never before —
     * and restore the previous value in a finally block.
     */
    private boolean skipDataScope = false;

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
        newContext.setSkipAutoAudit(this.skipAutoAudit);
        newContext.setCrossTenant(this.crossTenant);
        newContext.setDataMask(this.dataMask);
        newContext.setTriggerFlow(this.triggerFlow);
        newContext.setDebug(this.debug);
        newContext.setEffectiveDate(this.effectiveDate);
        return newContext;
    }

}
