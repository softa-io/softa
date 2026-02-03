package io.softa.starter.designer.service.impl;

import org.springframework.stereotype.Service;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.designer.entity.DesignFieldTypeMapping;
import io.softa.starter.designer.service.DesignFieldTypeMappingService;

/**
 * DesignFieldTypeMapping Model Service Implementation
 */
@Service
public class DesignFieldTypeMappingServiceImpl extends EntityServiceImpl<DesignFieldTypeMapping, Long> implements DesignFieldTypeMappingService {

}