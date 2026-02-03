package io.softa.starter.file.dto;

import lombok.Data;

import io.softa.framework.orm.domain.Filters;

@Data
public class ExportTemplateDTO {

    private String templateId;

    private Filters filters;

}
