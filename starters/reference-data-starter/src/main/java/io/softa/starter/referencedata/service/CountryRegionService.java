package io.softa.starter.referencedata.service;

import java.util.List;
import java.util.Optional;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.referencedata.entity.CountryRegion;
import io.softa.starter.referencedata.enums.Continent;

/**
 * CRUD + lookup service for {@link CountryRegion}. {@link #findByCode(String)}
 * is the primary access path and is cached.
 */
public interface CountryRegionService extends EntityService<CountryRegion, Long> {

    /**
     * Primary lookup by ISO 3166-1 alpha-2 code. Cached.
     *
     * @param code ISO 3166-1 alpha-2 (CN/US/TW/...); case-sensitive
     * @return the matching country, or empty when not found
     */
    Optional<CountryRegion> findByCode(String code);

    /** Enabled countries within a continent, ordered by code asc. */
    List<CountryRegion> findByContinent(Continent continent);

    /** EEA member countries (EU 27 + IS/LI/NO), ordered by code asc. */
    List<CountryRegion> findEeaMembers();
}
