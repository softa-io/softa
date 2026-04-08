package io.softa.starter.studio.template.service.impl;

import org.springframework.stereotype.Service;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.studio.template.entity.DesignFieldDbMapping;
import io.softa.starter.studio.template.service.DesignFieldDbMappingService;

/**
 * DesignFieldDbMapping Model Service Implementation
 */
@Service
public class DesignFieldDbMappingServiceImpl extends EntityServiceImpl<DesignFieldDbMapping, Long> implements DesignFieldDbMappingService {

}
