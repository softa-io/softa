package io.softa.starter.studio.meta.service.impl;

import org.springframework.stereotype.Service;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.studio.meta.entity.DesignField;
import io.softa.starter.studio.meta.service.DesignFieldService;

/**
 * DesignField Model Service Implementation
 */
@Service
public class DesignFieldServiceImpl extends EntityServiceImpl<DesignField, Long> implements DesignFieldService {

}