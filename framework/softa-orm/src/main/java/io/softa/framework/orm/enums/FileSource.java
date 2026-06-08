package io.softa.framework.orm.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import io.softa.framework.orm.annotation.OptionItem;
import io.softa.framework.orm.annotation.OptionSet;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
@OptionSet(label = "File Source")
public enum FileSource {
    DOWNLOAD("Download"),
    UPLOAD("Upload"),
    @OptionItem(label = "URL")
    URL("URL"),
            ;

    @JsonValue
    private final String code;
}
