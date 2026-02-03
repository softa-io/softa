package io.softa.starter.metadata.service.impl;

import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.metadata.entity.SysModel;
import io.softa.starter.metadata.service.SysModelService;
import org.springframework.stereotype.Service;

/**
 * SysModel Model Service Implementation
 */
@Service
public class SysModelServiceImpl extends EntityServiceImpl<SysModel, Long> implements SysModelService {

}