package io.softa.starter.studio.template.service.impl;

import org.springframework.stereotype.Service;

import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.studio.template.entity.DesignFieldCodeMapping;
import io.softa.starter.studio.template.service.DesignFieldCodeMappingService;

/**
 * DesignFieldCodeMapping Model Service Implementation
 */
@Service
public class DesignFieldCodeMappingServiceImpl extends EntityServiceImpl<DesignFieldCodeMapping, Long> implements DesignFieldCodeMappingService {

}
