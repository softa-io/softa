package io.softa.starter.file.dto;

import lombok.Data;

import io.softa.framework.orm.domain.Filters;

@Data
public class ExportTemplateDTO {

    private Long templateId;

    private Filters filters;

}
