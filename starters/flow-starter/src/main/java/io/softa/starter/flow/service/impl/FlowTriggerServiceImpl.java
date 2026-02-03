package io.softa.starter.flow.service.impl;

import org.springframework.stereotype.Service;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.flow.entity.FlowTrigger;
import io.softa.starter.flow.service.FlowTriggerService;

/**
 * FlowTrigger Model Service Implementation
 */
@Service
public class FlowTriggerServiceImpl extends EntityServiceImpl<FlowTrigger, String> implements FlowTriggerService {

}