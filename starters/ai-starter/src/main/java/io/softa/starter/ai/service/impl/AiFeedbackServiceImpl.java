package io.softa.starter.ai.service.impl;

import org.springframework.stereotype.Service;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.ai.entity.AiFeedback;
import io.softa.starter.ai.service.AiFeedbackService;

/**
 * AiFeedback Model Service Implementation
 */
@Service
public class AiFeedbackServiceImpl extends EntityServiceImpl<AiFeedback, String> implements AiFeedbackService {

}