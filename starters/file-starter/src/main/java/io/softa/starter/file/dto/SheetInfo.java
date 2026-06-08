package io.softa.starter.file.dto;

import lombok.Data;

import io.softa.framework.orm.domain.FlexQuery;

/**
 * The DTO of Excel sheet info.
 */
@Data
public class SheetInfo {
    private String modelName;

    private String sheetName;

    private FlexQuery flexQuery;
}
