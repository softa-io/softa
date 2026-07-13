package io.softa.starter.studio.meta.service.impl;

import org.springframework.stereotype.Service;

import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.studio.meta.entity.DesignFieldDomain;
import io.softa.starter.studio.meta.service.DesignFieldDomainService;

/**
 * DesignFieldDomain Model Service Implementation (renamed from DesignFieldTypeDefaultServiceImpl).
 */
@Service
public class DesignFieldDomainServiceImpl extends EntityServiceImpl<DesignFieldDomain, Long>
        implements DesignFieldDomainService {

}
