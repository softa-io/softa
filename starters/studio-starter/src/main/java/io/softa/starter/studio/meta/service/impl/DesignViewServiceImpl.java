package io.softa.starter.studio.meta.service.impl;

import org.springframework.stereotype.Service;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.studio.meta.entity.DesignView;
import io.softa.starter.studio.meta.service.DesignViewService;

/**
 * DesignView Model Service Implementation
 */
@Service
public class DesignViewServiceImpl extends EntityServiceImpl<DesignView, Long> implements DesignViewService {

}