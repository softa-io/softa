package io.softa.starter.designer.service.impl;

import org.springframework.stereotype.Service;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.designer.entity.DesignNavigation;
import io.softa.starter.designer.service.DesignNavigationService;

/**
 * DesignNavigation Model Service Implementation
 */
@Service
public class DesignNavigationServiceImpl extends EntityServiceImpl<DesignNavigation, Long> implements DesignNavigationService {

}