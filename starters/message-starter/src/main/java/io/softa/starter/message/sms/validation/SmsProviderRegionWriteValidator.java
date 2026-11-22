package io.softa.starter.message.sms.validation;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import io.softa.framework.orm.service.validation.ModelWriteValidator;
import io.softa.framework.orm.service.validation.WriteContext;
import io.softa.starter.referencedata.service.CountryRegionService;

/**
 * A route's country must be one the country table knows, on every write path: the Country Routing
 * page, the routes edited on a provider's page (written as that provider's one-to-many rows), import,
 * seed loading and a direct service call. {@code regionCode} is a relation onto the code-as-id
 * {@code CountryRegion}, which renders the country picker but does not make the database refuse a
 * code that is not there.
 */
@Component
@Order(150)
public class SmsProviderRegionWriteValidator implements ModelWriteValidator {

    static final String MODEL = "SmsProviderRegion";
    static final String REGION_CODE = "regionCode";

    private final CountryRegionService countryRegionService;

    public SmsProviderRegionWriteValidator(CountryRegionService countryRegionService) {
        this.countryRegionService = countryRegionService;
    }

    @Override
    public boolean supports(String modelName) {
        return MODEL.equals(modelName);
    }

    @Override
    public void validateCreate(WriteContext ctx) {
        checkRegionCode(ctx);
    }

    @Override
    public void validateUpdate(WriteContext ctx) {
        if (ctx.patch().containsKey(REGION_CODE)) {
            checkRegionCode(ctx);
        }
    }

    private void checkRegionCode(WriteContext ctx) {
        // A missing value is the required check's to report; this rule is about a value that is there.
        if (!(ctx.row().get(REGION_CODE) instanceof String code) || code.isBlank()) {
            return;
        }
        if (countryRegionService.findByCode(code.trim()).isEmpty()) {
            ctx.reject(REGION_CODE, "Unknown country/region code " + code.trim()
                    + ". Load the country/region reference data first.");
        }
    }
}
