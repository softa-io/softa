package io.softa.starter.referencedata.service.impl;

import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.referencedata.entity.CountryRegion;
import io.softa.starter.referencedata.enums.Continent;
import io.softa.starter.referencedata.service.CountryRegionService;
import io.softa.starter.referencedata.support.CountryRegionCache;

@Service
public class CountryRegionServiceImpl extends EntityServiceImpl<CountryRegion, Long>
        implements CountryRegionService {

    @Autowired
    private CountryRegionCache cache;

    @Override
    public Optional<CountryRegion> findByCode(String code) {
        return Optional.ofNullable(cache.getByCode(code, () -> {
            Filters filters = new Filters().eq(CountryRegion::getCode, code);
            return this.searchOne(new FlexQuery(filters)).orElse(null);
        }));
    }

    @Override
    public List<CountryRegion> findByContinent(Continent continent) {
        Filters filters = new Filters().eq(CountryRegion::getContinent, continent);
        return this.searchList(new FlexQuery(filters, Orders.ofAsc(CountryRegion::getCode)));
    }

    @Override
    public List<CountryRegion> findEeaMembers() {
        Filters filters = new Filters().eq(CountryRegion::getEea, true);
        return this.searchList(new FlexQuery(filters, Orders.ofAsc(CountryRegion::getCode)));
    }

    @Override
    public boolean updateOne(CountryRegion entity) {
        boolean result = super.updateOne(entity);
        if (result) cache.evictByCode(entity.getCode());
        return result;
    }

    @Override
    public boolean deleteById(Long id) {
        Optional<CountryRegion> existing = this.getById(id);
        boolean result = super.deleteById(id);
        if (result && existing.isPresent()) cache.evictByCode(existing.get().getCode());
        return result;
    }
}
