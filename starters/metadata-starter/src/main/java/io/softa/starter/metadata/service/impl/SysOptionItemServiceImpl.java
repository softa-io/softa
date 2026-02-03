package io.softa.starter.metadata.service.impl;

import org.springframework.stereotype.Service;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.metadata.entity.SysOptionItem;
import io.softa.starter.metadata.service.SysOptionItemService;

/**
 * SysOptionItem Model Service Implementation
 */
@Service
public class SysOptionItemServiceImpl extends EntityServiceImpl<SysOptionItem, Long> implements SysOptionItemService {

}