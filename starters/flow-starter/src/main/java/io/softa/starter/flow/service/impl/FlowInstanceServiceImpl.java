package io.softa.starter.flow.service.impl;

import org.springframework.stereotype.Service;

import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.service.FlowInstanceService;

/**
 * FlowInstance Model Service Implementation
 */
@Service
public class FlowInstanceServiceImpl extends EntityServiceImpl<FlowInstance, Long> implements FlowInstanceService {

}