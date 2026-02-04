package io.softa.app.demo.service.impl;

import org.springframework.stereotype.Service;

import io.softa.app.demo.entity.DeptInfo;
import io.softa.app.demo.service.DeptInfoService;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.service.impl.EntityServiceImpl;

/**
 * DeptInfo Model Service Implementation
 */
@Service
public class DeptInfoServiceImpl extends EntityServiceImpl<DeptInfo, Long> implements DeptInfoService {

    public DeptInfo getDeptInfoByCode(String code) {
        Filters filters = new Filters().eq("code", code);
        FlexQuery flexQuery = new FlexQuery().where(filters)
                .select(DeptInfo::getName, DeptInfo::getCode, DeptInfo::getDescription, DeptInfo::getActive)
                .orderBy(Orders.ofAsc(DeptInfo::getId).addAsc(DeptInfo::getName));
        return this.searchOne(flexQuery).orElse(null);
    }
}