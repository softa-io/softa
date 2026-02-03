package io.softa.starter.designer.service.impl;

import org.springframework.stereotype.Service;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.designer.entity.DesignView;
import io.softa.starter.designer.service.DesignViewService;

/**
 * DesignView Model Service Implementation
 */
@Service
public class DesignViewServiceImpl extends EntityServiceImpl<DesignView, Long> implements DesignViewService {

}