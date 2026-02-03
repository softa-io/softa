package io.softa.starter.flow.service.impl;

import org.springframework.stereotype.Service;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.flow.entity.FlowEvent;
import io.softa.starter.flow.service.FlowEventService;

/**
 * FlowEvent Model Service Implementation
 */
@Service
public class FlowEventServiceImpl extends EntityServiceImpl<FlowEvent, String> implements FlowEventService {

}