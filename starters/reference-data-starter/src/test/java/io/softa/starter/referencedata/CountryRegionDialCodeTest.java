package io.softa.starter.referencedata;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every country's {@code dialCode} in the seed is the calling code libphonenumber gives it.
 * <p>
 * Phone inputs build a number as {@code "+" + dialCode + national number}, and parse it back with
 * libphonenumber, whose national number of a NANP territory includes its area code. A dial code
 * that carries the area code as well ("1787" for Puerto Rico) therefore writes it twice
 * (+17877872345678) and cannot express the territory's other area codes (939). Countries
 * libphonenumber does not know (Antarctica, Bouvet Island, ...) keep the code the seed gives them.
 */
class CountryRegionDialCodeTest {

    private static final String SEED = "/data-system/CountryRegion.AllCountries.json";

    @Test
    void dialCodeIsTheCallingCodeOfEveryCountryLibphonenumberKnows() throws Exception {
        PhoneNumberUtil util = PhoneNumberUtil.getInstance();
        JsonNode rows;
        try (InputStream in = CountryRegionDialCodeTest.class.getResourceAsStream(SEED)) {
            Assertions.assertNotNull(in, SEED);
            rows = JsonMapper.builder().build().readTree(in).path("CountryRegion");
        }
        Assertions.assertTrue(rows.size() > 200, "seed rows: " + rows.size());

        List<String> mismatches = new ArrayList<>();
        for (JsonNode row : rows) {
            String id = row.path("id").asString();
            int callingCode = util.getCountryCodeForRegion(id);
            if (callingCode != 0 && !String.valueOf(callingCode).equals(row.path("dialCode").asString())) {
                mismatches.add(id + ": " + row.path("dialCode").asString() + " should be " + callingCode);
            }
        }
        Assertions.assertEquals(List.of(), mismatches);
    }
}
