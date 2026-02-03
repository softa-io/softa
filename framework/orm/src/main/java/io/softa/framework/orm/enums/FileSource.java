package io.softa.framework.orm.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum FileSource {
    DOWNLOAD("Download"),
    UPLOAD("Upload"),
    URL("URL"),
            ;

    @JsonValue
    private final String code;
}
