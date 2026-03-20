package io.softa.starter.file.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum DocumentTemplateType {
    RICH_TEXT("RichText", "Online Rich Text Editor"),
    WORD("Word", "Upload a Word template"),
    PDF("PDF", "Upload a PDF template"),
    ;

    @JsonValue
    private final String type;
    private final String description;
}
