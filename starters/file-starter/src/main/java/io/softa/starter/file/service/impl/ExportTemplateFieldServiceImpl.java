package io.softa.starter.file.service.impl;

import org.springframework.stereotype.Service;

import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.file.entity.ExportTemplateField;
import io.softa.starter.file.service.ExportTemplateFieldService;

/**
 * ExportTemplateField Model Service Implementation
 */
@Service
public class ExportTemplateFieldServiceImpl extends EntityServiceImpl<ExportTemplateField, Long> implements ExportTemplateFieldService {

}