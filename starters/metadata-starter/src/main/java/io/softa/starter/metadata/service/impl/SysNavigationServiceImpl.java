package io.softa.starter.metadata.service.impl;

import org.springframework.stereotype.Service;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.metadata.entity.SysNavigation;
import io.softa.starter.metadata.service.SysNavigationService;

/**
 * SysNavigation Model Service Implementation
 */
@Service
public class SysNavigationServiceImpl extends EntityServiceImpl<SysNavigation, Long> implements SysNavigationService {

}