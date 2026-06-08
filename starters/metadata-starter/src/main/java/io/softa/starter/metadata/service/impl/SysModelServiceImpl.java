package io.softa.starter.metadata.service.impl;

import org.springframework.stereotype.Service;

import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.metadata.entity.SysModel;
import io.softa.starter.metadata.service.SysModelService;

/**
 * SysModel Model Service Implementation
 */
@Service
public class SysModelServiceImpl extends EntityServiceImpl<SysModel, Long> implements SysModelService {

}