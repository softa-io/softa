package io.softa.starter.referencedata.service.impl;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.softa.framework.orm.domain.FlexQuery;
import io.softa.starter.referencedata.entity.CountrySubdivision;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

class CountrySubdivisionServiceImplTest {

    private CountrySubdivisionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = spy(new CountrySubdivisionServiceImpl());
    }

    @Test
    void findByCodeReturnsEntity() {
        CountrySubdivision sub = subdivision("CN-31", "CN", "Shanghai", null, "municipality");
        doReturn(Optional.of(sub)).when(service).searchOne(any(FlexQuery.class));

        Optional<CountrySubdivision> result = service.findByCode("CN-31");

        Assertions.assertTrue(result.isPresent());
        Assertions.assertEquals("CN-31", result.get().getCode());
        Assertions.assertEquals("CN", result.get().getCountryCode());
    }

    @Test
    void findByCodeReturnsEmptyWhenAbsent() {
        doReturn(Optional.empty()).when(service).searchOne(any(FlexQuery.class));
        Assertions.assertTrue(service.findByCode("ZZ-99").isEmpty());
    }

    @Test
    void findByCountryCodeReturnsList() {
        List<CountrySubdivision> rows = List.of(
                subdivision("US-CA", "US", "California", null, "state"),
                subdivision("US-NY", "US", "New York", null, "state"),
                subdivision("US-TX", "US", "Texas", null, "state"));
        doReturn(rows).when(service).searchList(any(FlexQuery.class));

        List<CountrySubdivision> result = service.findByCountryCode("US");

        Assertions.assertEquals(3, result.size());
        Assertions.assertTrue(result.stream().allMatch(s -> "US".equals(s.getCountryCode())));
    }

    @Test
    void findByCountryCodeReturnsEmptyForUnpopulatedCountry() {
        // Subdivisions table is unseeded in this release; most countries will
        // return an empty list — guard the API surface against null.
        doReturn(List.of()).when(service).searchList(any(FlexQuery.class));
        Assertions.assertTrue(service.findByCountryCode("CN").isEmpty());
    }

    @Test
    void findByParentCodeReturnsChildren() {
        // CN-31 (Shanghai) prefecture-level cities — illustrative hierarchy.
        List<CountrySubdivision> children = List.of(
                subdivision("CN-31-A", "CN", "Pudong", "CN-31", "district"),
                subdivision("CN-31-B", "CN", "Huangpu", "CN-31", "district"));
        doReturn(children).when(service).searchList(any(FlexQuery.class));

        List<CountrySubdivision> result = service.findByParentCode("CN-31");

        Assertions.assertEquals(2, result.size());
        Assertions.assertTrue(result.stream().allMatch(s -> "CN-31".equals(s.getParentCode())));
    }

    // ---- helpers ----

    private static CountrySubdivision subdivision(String code, String countryCode, String name,
                                                  String parentCode, String type) {
        CountrySubdivision s = new CountrySubdivision();
        s.setCode(code);
        s.setCountryCode(countryCode);
        s.setName(name);
        s.setParentCode(parentCode);
        s.setType(type);
        return s;
    }
}
