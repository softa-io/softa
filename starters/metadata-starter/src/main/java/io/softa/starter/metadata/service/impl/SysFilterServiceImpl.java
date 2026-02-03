package io.softa.starter.metadata.service.impl;

import org.springframework.stereotype.Service;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.metadata.entity.SysFilter;
import io.softa.starter.metadata.service.SysFilterService;

/**
 * SysFilter Model Service Implementation
 */
@Service
public class SysFilterServiceImpl extends EntityServiceImpl<SysFilter, Long> implements SysFilterService {

}