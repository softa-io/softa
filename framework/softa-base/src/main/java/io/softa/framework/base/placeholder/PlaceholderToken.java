package io.softa.framework.base.placeholder;

import lombok.Getter;

@Getter
public final class PlaceholderToken {
    private final PlaceholderKind kind;
    private final String content;

    public PlaceholderToken(PlaceholderKind kind, String content) {
        this.kind = kind;
        this.content = content;
    }

}
