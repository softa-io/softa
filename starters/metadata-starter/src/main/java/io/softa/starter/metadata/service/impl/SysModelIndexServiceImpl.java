package io.softa.starter.metadata.service.impl;

import org.springframework.stereotype.Service;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.metadata.entity.SysModelIndex;
import io.softa.starter.metadata.service.SysModelIndexService;

/**
 * SysModelIndex Model Service Implementation
 */
@Service
public class SysModelIndexServiceImpl extends EntityServiceImpl<SysModelIndex, Long> implements SysModelIndexService {

}