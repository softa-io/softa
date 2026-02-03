package io.softa.starter.ai.service.impl;

import org.springframework.stereotype.Service;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.ai.entity.AiModel;
import io.softa.starter.ai.service.AiModelService;

/**
 * AiModel Model Service Implementation
 */
@Service
public class AiModelServiceImpl extends EntityServiceImpl<AiModel, String> implements AiModelService {

}