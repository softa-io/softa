package io.softa.starter.designer.service.impl;

import org.springframework.stereotype.Service;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.designer.entity.DesignField;
import io.softa.starter.designer.service.DesignFieldService;

/**
 * DesignField Model Service Implementation
 */
@Service
public class DesignFieldServiceImpl extends EntityServiceImpl<DesignField, Long> implements DesignFieldService {

}