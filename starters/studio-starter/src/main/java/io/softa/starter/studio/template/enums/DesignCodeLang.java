package io.softa.starter.studio.template.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

import io.softa.framework.base.exception.IllegalArgumentException;

@Getter
@AllArgsConstructor
public enum DesignCodeLang {
    JAVA("Java", "Java", ".java"),
    RUST("Rust", "Rust", ".rs"),
    GOLANG("Golang", "Golang", ".go"),
    TYPESCRIPT("TypeScript", "TypeScript", ".ts"),
    PYTHON("Python", "Python", ".py"),
    CSHARP("Csharp", "C#", ".cs"),
    RUBY("Ruby", "Ruby", ".rb"),
    ;

    @JsonValue
    private final String code;

    private final String description;

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
