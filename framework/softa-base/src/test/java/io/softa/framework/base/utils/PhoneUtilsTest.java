package io.softa.framework.base.utils;

import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.softa.framework.base.exception.BusinessException;

class PhoneUtilsTest {

    @Test
    void toE164DropsTheSeparatorsPeopleType() {
        Assertions.assertEquals("+6591234567", PhoneUtils.toE164("+6591234567"));
        Assertions.assertEquals("+6591234567", PhoneUtils.toE164(" +65 9123-4567 "));
        Assertions.assertEquals("+6591234567", PhoneUtils.toE164("+(65) 9123 4567"));
        Assertions.assertEquals("+6591234567", PhoneUtils.toE164("＋65 9123 4567"));
        Assertions.assertEquals("+6421123456", PhoneUtils.toE164("+64 21 123 456"));
        Assertions.assertEquals("+17872345678", PhoneUtils.toE164("+1 787 234 5678"));
    }

    @Test
    void toE164KeepsANumberInARangeTheBundledMetadataDoesNotKnow() {
        // Singapore has not opened 4xxx: a range it opens later must not be refused before an upgrade.
        Assertions.assertEquals("+6541234567", PhoneUtils.toE164("+65 4123 4567"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "91234567", "6591234567", "006591234567", "+65 9123 4567 abc",
            "+65 9123 4567 ext. 12", "tel:+65-9123-4567", "+999 12345678", "+"})
    void toE164RefusesANumberWithoutItsCountryCodeOrWithLetters(String typed) {
        BusinessException e = Assertions.assertThrows(BusinessException.class, () -> PhoneUtils.toE164(typed));
        Assertions.assertTrue(e.getMessage().contains("country code"), e.getMessage());
    }

    @Test
    void toE164RefusesNull() {
        Assertions.assertThrows(BusinessException.class, () -> PhoneUtils.toE164(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"+65 912 3456", "+65 9123 45678", "+64 2112"})
    void toE164RefusesTheWrongNumberOfDigitsForTheCountry(String typed) {
        BusinessException e = Assertions.assertThrows(BusinessException.class, () -> PhoneUtils.toE164(typed));
        Assertions.assertTrue(e.getMessage().contains("number of digits"), e.getMessage());
    }

    @Test
    void regionOfMatchesTheLongestRange() {
        Assertions.assertEquals("SG", PhoneUtils.regionOf("+6591234567"));
        Assertions.assertEquals("NZ", PhoneUtils.regionOf("+6421123456"));
        Assertions.assertEquals("TW", PhoneUtils.regionOf("+886912345678"));
        Assertions.assertEquals("CN", PhoneUtils.regionOf("+8613800138000"));
        Assertions.assertEquals("PR", PhoneUtils.regionOf("+17872345678"));
        Assertions.assertEquals("PR", PhoneUtils.regionOf("+19392345678"));
        Assertions.assertEquals("CA", PhoneUtils.regionOf("+14165551234"));
        Assertions.assertEquals("KZ", PhoneUtils.regionOf("+77012345678"));
    }

    @Test
    void regionOfRefusesWhatItCannotPlace() {
        // A shared calling code and a range no country of it has.
        Assertions.assertThrows(BusinessException.class, () -> PhoneUtils.regionOf("+441000000000"));
        Assertions.assertThrows(BusinessException.class, () -> PhoneUtils.regionOf("91234567"));
        Assertions.assertThrows(BusinessException.class, () -> PhoneUtils.regionOf("not-a-phone-number"));
    }

    @Test
    void splitGivesTheCallingCodeNationalNumberAndCountry() {
        Assertions.assertEquals(Optional.of(new PhoneUtils.Parts("65", "91234567", "SG")),
                PhoneUtils.split("+65 9123 4567"));
        // NANP: the area code belongs to the national number, the calling code is 1.
        Assertions.assertEquals(Optional.of(new PhoneUtils.Parts("1", "7872345678", "PR")),
                PhoneUtils.split("+17872345678"));
        Assertions.assertEquals(Optional.of(new PhoneUtils.Parts("44", "1000000000", null)),
                PhoneUtils.split("+441000000000"));
        Assertions.assertEquals(Optional.empty(), PhoneUtils.split("91234567"));
        Assertions.assertEquals(Optional.empty(), PhoneUtils.split("+659123****"));
        Assertions.assertEquals(Optional.empty(), PhoneUtils.split(null));
    }

    @Test
    void isE164ChecksTheStoredShapeOnly() {
        Assertions.assertTrue(PhoneUtils.isE164("+6591234567"));
        Assertions.assertTrue(PhoneUtils.isE164(" +8613800138000 "));
        Assertions.assertFalse(PhoneUtils.isE164("+65 9123 4567"));
        Assertions.assertFalse(PhoneUtils.isE164("91234567"));
        Assertions.assertFalse(PhoneUtils.isE164("+0123456789"));
        Assertions.assertFalse(PhoneUtils.isE164("+6232123314611320"));
        Assertions.assertFalse(PhoneUtils.isE164(null));
    }
}
