package io.softa.starter.flow.service.impl;

import org.springframework.stereotype.Service;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.flow.entity.FlowDebugHistory;
import io.softa.starter.flow.service.FlowDebugHistoryService;

/**
 * FlowDebugHistory Model Service Implementation
 */
@Service
public class FlowDebugHistoryServiceImpl extends EntityServiceImpl<FlowDebugHistory, String> implements FlowDebugHistoryService {

}