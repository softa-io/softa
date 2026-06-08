package io.softa.starter.studio.template.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.orm.annotation.OptionItem;
import io.softa.framework.orm.annotation.OptionSet;

@Getter
@AllArgsConstructor
@OptionSet(label = "Design Code Lang")
public enum DesignCodeLang {
    JAVA("Java", ".java"),
    RUST("Rust", ".rs"),
    GOLANG("Golang", ".go"),
    @OptionItem(label = "TypeScript")
    TYPESCRIPT("TypeScript", ".ts"),
    PYTHON("Python", ".py"),
    @OptionItem(label = "C#")
    CSHARP("Csharp", ".cs"),
    RUBY("Ruby", ".rb"),
    ;

    @JsonValue
    private final String code;

    private final String fileExtension;

    public static DesignCodeLang of(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (DesignCodeLang codeLang : values()) {
            if (codeLang.name().equalsIgnoreCase(value) || codeLang.code.equalsIgnoreCase(value)) {
                return codeLang;
            }
        }
        throw new IllegalArgumentException("The design code language {0} is not supported!", value);
    }
}
