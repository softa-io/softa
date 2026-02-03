package io.softa.starter.file.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.file.entity.ExportTemplate;
import io.softa.starter.file.service.ExportTemplateService;

/**
 * Export template service implementation class
 */
@Service
@Slf4j
public class ExportTemplateServiceImpl extends EntityServiceImpl<ExportTemplate, String> implements ExportTemplateService {

}