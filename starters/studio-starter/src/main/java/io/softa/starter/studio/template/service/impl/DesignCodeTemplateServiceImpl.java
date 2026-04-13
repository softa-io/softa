package io.softa.starter.studio.template.service.impl;

import org.springframework.stereotype.Service;

import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.studio.template.entity.DesignCodeTemplate;
import io.softa.starter.studio.template.service.DesignCodeTemplateService;

/**
 * DesignCodeTemplate Model Service Implementation
 */
@Service
public class DesignCodeTemplateServiceImpl extends EntityServiceImpl<DesignCodeTemplate, Long> implements DesignCodeTemplateService {

}
