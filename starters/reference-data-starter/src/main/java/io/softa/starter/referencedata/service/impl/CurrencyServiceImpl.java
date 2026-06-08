package io.softa.starter.referencedata.service.impl;

import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.referencedata.entity.Currency;
import io.softa.starter.referencedata.service.CurrencyService;
import io.softa.starter.referencedata.support.CurrencyCache;

@Service
public class CurrencyServiceImpl extends EntityServiceImpl<Currency, Long>
        implements CurrencyService {

    @Autowired
    private CurrencyCache cache;

    @Override
    public Optional<Currency> findByCode(String code) {
        return Optional.ofNullable(cache.getByCode(code, () -> {
            Filters filters = new Filters().eq(Currency::getCode, code);
            return this.searchOne(new FlexQuery(filters)).orElse(null);
        }));
    }

    @Override
    public boolean updateOne(Currency entity) {
        boolean result = super.updateOne(entity);
        if (result) cache.evictByCode(entity.getCode());
        return result;
    }

    @Override
    public boolean deleteById(Long id) {
        Optional<Currency> existing = this.getById(id);
        boolean result = super.deleteById(id);
        if (result && existing.isPresent()) cache.evictByCode(existing.get().getCode());
        return result;
    }
}
