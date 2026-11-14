package io.softa.framework.orm.file;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * {@code file.*}: the secret {@link FileOpenKeys} signs file keys with.
 *
 * <p>No {@code toString}: nothing that prints this object may print the secret.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "file")
@Validated
public class FileOpenKeyProperties {

    /**
     * Signs every file key handed out in {@code FileInfo.url}. Required, and the same value on every
     * instance: a key one instance signed has to open on any other. Changing it invalidates every key
     * already handed out — a page refresh issues new ones. Generate it with
     * {@code openssl rand -base64 48}.
     */
    @NotBlank(message = "file.open-key-secret must be set: a random string of at least 32 characters, "
            + "the same on every instance. Generate one with: openssl rand -base64 48")
    @Size(min = 32, message = "file.open-key-secret is too short: use at least 32 characters. "
            + "Generate one with: openssl rand -base64 48")
    private String openKeySecret;
}
