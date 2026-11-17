package io.softa.starter.message.sms.support;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.framework.base.exception.BusinessException;
import io.softa.starter.message.sms.entity.SmsProviderConfig;
import io.softa.starter.message.sms.entity.SmsProviderRegion;
import io.softa.starter.message.sms.service.SmsProviderConfigService;
import io.softa.starter.message.sms.service.SmsProviderRegionService;

/**
 * Resolves the effective SMS provider config for sending.
 * <p>
 * <b>Country-aware resolution</b> (preferred entry point —
 * {@link #resolveProviders(String)}): the recipient's country, parsed from the
 * E.164 phone number, selects the enabled {@link SmsProviderRegion} rows for
 * that country; the enabled {@link SmsProviderConfig} rows they reference are
 * the candidates, ordered by route priority asc, then config id. A country with
 * no such row is not supported, and resolving it throws
 * {@link BusinessException} — there is no catchall provider, so a number is
 * never sent through an account nobody routed to its country.
 * Template-provider bindings are resolved later by {@link SmsRoutingPlanner};
 * they do not bypass country eligibility.
 *
 * <p><b>Explicit provider resolution</b>
 * ({@link #resolveProviderById(Long)}): used by code paths that already carry
 * a provider identity, such as an explicitly pinned provider or a retry.
 */
@Component
public class SmsProviderDispatcher {

    @Autowired
    private SmsProviderConfigService configService;

    @Autowired
    private SmsProviderRegionService regionService;

    @Autowired
    private SmsConfigCache configCache;

    /**
     * Country-aware: resolve every eligible provider for an E.164 phone number,
     * ordered by routing priority. This is used by template-aware planning, where
     * the country route is intersected with template-provider bindings before a
     * single provider is selected.
     */
    public List<SmsProviderConfig> resolveProviders(String e164PhoneNumber) {
        String region = resolveRegion(e164PhoneNumber);

        // Region rows arrive ordered by priority asc; take the enabled configs they reference.
        List<SmsProviderRegion> rows = regionService.findEnabledByRegion(region);
        List<ProviderCandidate> candidates = new ArrayList<>();
        for (SmsProviderRegion row : rows) {
            Long configId = row.getProviderConfigId();
            SmsProviderConfig c = configCache.getById(configId,
                    () -> configService.getById(configId).orElse(null));
            // The by-id cache read bypasses active control by design, so the
            // disabled-route check stays explicit here.
            if (c != null && Boolean.TRUE.equals(c.getActive())) {
                candidates.add(new ProviderCandidate(c, row.getPriority()));
            }
        }
        if (candidates.isEmpty()) {
            throw new BusinessException(
                    "No SMS provider serves region {0}: no active sms_provider_region row for {0} "
                  + "points to an active provider config.", region);
        }
        candidates.sort(Comparator
                .comparingInt(ProviderCandidate::routePriority)
                .thenComparingLong(p -> p.config().getId()));
        return candidates.stream().map(ProviderCandidate::config).toList();
    }

    /** Parse and return the ISO 3166-1 alpha-2 region for an E.164 phone number. */
    public String resolveRegion(String e164PhoneNumber) {
        return parseRegion(e164PhoneNumber);
    }

    /**
     * Resolve a specific provider config by ID, bypassing all dispatch logic.
     * Used when a caller explicitly pins {@code providerConfigId}, and when a
     * persisted send record is retried through the same provider.
     */
    public SmsProviderConfig resolveProviderById(Long id) {
        // By id, active or not: a prepared record replays through the very
        // provider it was routed to, even one disabled since.
        SmsProviderConfig config = configCache.getById(id,
                () -> configService.getById(id).orElse(null));
        if (config == null) {
            throw new BusinessException(
                    "SMS provider config with ID {0} not found.", id);
        }
        return config;
    }

    /**
     * Parse the recipient's region from an E.164 phone number. Uses
     * libphonenumber's longest-prefix matching so e.g. {@code +886...} → TW
     * (not falsely matched as {@code +86} → CN). Returns the ISO 3166-1
     * alpha-2 code string directly; downstream lookups against
     * {@code country_region} happen at the service layer.
     */
    private String parseRegion(String e164PhoneNumber) {
        if (!StringUtils.hasText(e164PhoneNumber)) {
            throw new BusinessException("Phone number is required for country-aware SMS dispatch");
        }
        try {
            PhoneNumberUtil pnu = PhoneNumberUtil.getInstance();
            Phonenumber.PhoneNumber parsed = pnu.parse(e164PhoneNumber, null);
            String code = pnu.getRegionCodeForNumber(parsed);
            if (code == null || "ZZ".equals(code)) {
                throw new BusinessException("Unable to determine ISO region for phone number {0}",
                        e164PhoneNumber);
            }
            return code;
        } catch (NumberParseException e) {
            throw new BusinessException("Invalid E.164 phone number {0}: {1}",
                    e164PhoneNumber, e.getMessage());
        }
    }

    private record ProviderCandidate(SmsProviderConfig config, int routePriority) {}

}
