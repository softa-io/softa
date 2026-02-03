package io.softa.starter.file.service.impl;

import org.springframework.stereotype.Service;

import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.file.entity.ImportTemplateField;
import io.softa.starter.file.service.ImportTemplateFieldService;

/**
 * ImportTemplateField Model Service Implementation
 */
@Service
public class ImportTemplateFieldServiceImpl extends EntityServiceImpl<ImportTemplateField, String> implements ImportTemplateFieldService {

}