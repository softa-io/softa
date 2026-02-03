package io.softa.starter.flow.service.impl;

import org.springframework.stereotype.Service;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.flow.entity.FlowStage;
import io.softa.starter.flow.service.FlowStageService;

/**
 * FlowStage Model Service Implementation
 */
@Service
public class FlowStageServiceImpl extends EntityServiceImpl<FlowStage, String> implements FlowStageService {

}