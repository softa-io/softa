package io.softa.starter.flow.service.impl;

import org.springframework.stereotype.Service;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.flow.entity.FlowEdge;
import io.softa.starter.flow.service.FlowEdgeService;

/**
 * FlowEdge Model Service Implementation
 */
@Service
public class FlowEdgeServiceImpl extends EntityServiceImpl<FlowEdge, String> implements FlowEdgeService {

}