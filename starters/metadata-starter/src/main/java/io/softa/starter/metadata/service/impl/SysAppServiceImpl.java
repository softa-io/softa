package io.softa.starter.metadata.service.impl;

import org.springframework.stereotype.Service;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.metadata.entity.SysApp;
import io.softa.starter.metadata.service.SysAppService;

/**
 * SysApp Model Service Implementation
 */
@Service
public class SysAppServiceImpl extends EntityServiceImpl<SysApp, Long> implements SysAppService {

}