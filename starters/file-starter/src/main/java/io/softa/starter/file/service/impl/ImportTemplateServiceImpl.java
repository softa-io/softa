package io.softa.starter.file.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.file.entity.ImportTemplate;
import io.softa.starter.file.service.ImportTemplateService;

/**
 * ImportTemplateService implementation
 */
@Slf4j
@Service
public class ImportTemplateServiceImpl extends EntityServiceImpl<ImportTemplate, Long> implements ImportTemplateService {

}