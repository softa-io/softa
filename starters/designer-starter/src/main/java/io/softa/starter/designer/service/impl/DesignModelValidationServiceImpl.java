package io.softa.starter.designer.service.impl;

import org.springframework.stereotype.Service;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.designer.entity.DesignModelValidation;
import io.softa.starter.designer.service.DesignModelValidationService;

/**
 * DesignModelValidation Model Service Implementation
 */
@Service
public class DesignModelValidationServiceImpl extends EntityServiceImpl<DesignModelValidation, Long> implements DesignModelValidationService {

}