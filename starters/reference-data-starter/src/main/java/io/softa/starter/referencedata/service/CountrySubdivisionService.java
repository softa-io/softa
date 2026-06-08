package io.softa.starter.referencedata.service;

import java.util.List;
import java.util.Optional;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.referencedata.entity.CountrySubdivision;

/**
 * CRUD + lookup service for {@link CountrySubdivision}. Interface is in
 * place this PR; data tables are empty until address/tax features populate
 * them.
 */
public interface CountrySubdivisionService extends EntityService<CountrySubdivision, Long> {

    /** Primary lookup by ISO 3166-2 full code (e.g. "CN-31", "US-CA"). */
    Optional<CountrySubdivision> findByCode(String code);

    /** All top-level subdivisions for a country, ordered by code asc. */
    List<CountrySubdivision> findByCountryCode(String countryCode);

    /** Direct children of a parent subdivision (e.g. cities under a Chinese province). */
    List<CountrySubdivision> findByParentCode(String parentCode);
}
