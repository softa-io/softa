package io.softa.starter.referencedata.validation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.service.validation.ModelWriteValidator;
import io.softa.framework.orm.service.validation.WriteContext;

/**
 * A company's time zone is one of its country's zones ({@code CountryTimeZone}).
 *
 * <ul>
 *   <li><b>Create</b> — an empty time zone is filled with the country's default zone, so a company
 *       created without one (an import, a tenant's first company, a direct API call) still opens
 *       and closes its periods on its own clock instead of the tenant's. A zone of another country
 *       is rejected.</li>
 *   <li><b>Update</b> — judged only when the patch touches the country or the time zone. The zone
 *       must then belong to the country: changing the country means choosing a zone of the new one
 *       too, which the company form does on its own. The update path fixes the columns it writes
 *       before validators run, so an update cannot be completed here the way a create is; it can
 *       only be refused.</li>
 * </ul>
 * A country with no zone at all (the uninhabited Bouvet and Heard Islands) accepts an empty time
 * zone and nothing else. A company saved before this rule keeps whatever it holds until its country
 * or time zone is next edited.
 *
 * <p>Applies to the application's {@code Company} model by name, whichever class declares it; the field it
 * judges is {@code BaseCompany.timeZone}.
 *
 * <p>Band 100: a field rule that needs a lookup.
 */
@Component
@Order(100)
public class CompanyTimeZoneValidator implements ModelWriteValidator {

    static final String MODEL = ModelConstant.COMPANY_MODEL;
    static final String ZONE_MODEL = "CountryTimeZone";
    static final String COUNTRY = "country";
    static final String TIME_ZONE = "timeZone";

    @Lazy
    @Autowired
    private ModelService<Long> modelService;

    @Override
    public boolean supports(String modelName) {
        return MODEL.equals(modelName);
    }

    @Override
    public void validateCreate(WriteContext ctx) {
        Map<String, Object> row = ctx.row();
        String country = idOf(row.get(COUNTRY));
        if (country == null || countryRefused(ctx)) {
            return; // the required-field check reports a missing country, the country's own validator a bad one
        }
        String zone = idOf(row.get(TIME_ZONE));
        Map<String, Boolean> zones = zonesOf(country);
        if (zone == null) {
            String fallback = defaultOf(zones);
            if (fallback != null) {
                // The create path writes the request row as it stands, so the default goes in with it.
                row.put(TIME_ZONE, fallback);
            }
            return;
        }
        if (!zones.containsKey(zone)) {
            ctx.reject(TIME_ZONE, notInCountry(zone, country));
        }
    }

    @Override
    public void validateUpdate(WriteContext ctx) {
        Map<String, Object> patch = ctx.patch();
        if (!patch.containsKey(COUNTRY) && !patch.containsKey(TIME_ZONE)) {
            return;
        }
        Map<String, Object> merged = ctx.mergedRow();
        String country = idOf(merged.get(COUNTRY));
        if (country == null || countryRefused(ctx)) {
            return;
        }
        String zone = idOf(merged.get(TIME_ZONE));
        Map<String, Boolean> zones = zonesOf(country);
        if (zone == null) {
            if (!zones.isEmpty()) {
                ctx.reject(TIME_ZONE, "Choose a time zone of " + country + ".");
            }
            return;
        }
        if (!zones.containsKey(zone)) {
            ctx.reject(TIME_ZONE, patch.containsKey(TIME_ZONE)
                    ? notInCountry(zone, country)
                    : "The time zone " + zone + " is not in " + country + ". Choose a time zone of " + country + ".");
        }
    }

    /**
     * Whether the country itself was already refused (not a country code). Its zones are then none, and
     * a second sentence about the time zone would only repeat the first.
     */
    private static boolean countryRefused(WriteContext ctx) {
        return ctx.errors().stream().anyMatch(error -> COUNTRY.equals(error.field()));
    }

    /** The default zone among a country's zones, or null when the country has none. */
    static String defaultOf(Map<String, Boolean> zones) {
        return zones.entrySet().stream()
                .filter(Map.Entry::getValue)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    /** Zone id → whether it is the country's default, in sequence order. */
    private Map<String, Boolean> zonesOf(String country) {
        // Reference data, read the same for every caller: the rule must not depend on whether the
        // user's roles happen to grant a scope on the zone list.
        Context unscoped = ContextHolder.cloneContext();
        unscoped.setSkipPermissionCheck(true);
        List<Map<String, Object>> rows = ContextHolder.callWith(unscoped, () ->
                modelService.searchList(ZONE_MODEL, new FlexQuery(List.of("id", "defaultZone"),
                        new Filters().eq(COUNTRY, country), Orders.ofAsc("sequence"))));
        Map<String, Boolean> zones = new LinkedHashMap<>();
        rows.forEach(r -> zones.put(String.valueOf(r.get("id")), Boolean.TRUE.equals(r.get("defaultZone"))));
        return zones;
    }

    private static String notInCountry(String zone, String country) {
        return "The time zone " + zone + " is not in " + country + ".";
    }

    /** A reference value as its id: the plain id, or the {@code {id, …}} object a form sends; null when blank. */
    static String idOf(Object value) {
        Object id = value instanceof Map<?, ?> map ? map.get("id") : value;
        if (id == null) {
            return null;
        }
        String text = String.valueOf(id).trim();
        return text.isEmpty() ? null : text;
    }
}
