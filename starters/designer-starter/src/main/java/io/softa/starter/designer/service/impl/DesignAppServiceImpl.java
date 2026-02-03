package io.softa.starter.designer.service.impl;

import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.designer.entity.DesignApp;
import io.softa.starter.designer.service.DesignAppService;
import org.springframework.stereotype.Service;

/**
 * DesignApp Model Service Implementation
 */
@Service
public class DesignAppServiceImpl extends EntityServiceImpl<DesignApp, Long> implements DesignAppService {

}