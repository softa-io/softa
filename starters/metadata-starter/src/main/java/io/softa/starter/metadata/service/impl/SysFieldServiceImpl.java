package io.softa.starter.metadata.service.impl;

import org.springframework.stereotype.Service;

import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.metadata.entity.SysField;
import io.softa.starter.metadata.service.SysFieldService;

/**
 * SysField Model Service Implementation
 */
@Service
public class SysFieldServiceImpl extends EntityServiceImpl<SysField, Long> implements SysFieldService {

}