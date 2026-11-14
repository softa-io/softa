package io.softa.framework.orm.file;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * {@code security.file-key.*}: the secret {@link FileOpenKeys} signs file keys with. The environment
 * variable {@code SECURITY_FILE_KEY_SECRET} binds to it.
 *
 * <p>Deliberately not {@code security.encryption.password}. That password can never change once data is
 * encrypted with it, while changing this secret is how every key handed out is revoked at once. And the
 * password is stretched before use, while an HMAC key is used as it is, so this one has to be long and
 * random.
 *
 * <p>No {@code toString}: nothing that prints this object may print the secret.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "security.file-key")
@Validated
public class FileOpenKeyProperties {

    /**
     * Signs every file key handed out in {@code FileInfo.url}. Required, and the same value on every
     * instance: a key one instance signed has to open on any other. Changing it invalidates every key
     * already handed out — a page refresh issues new ones. Generate it with
     * {@code openssl rand -base64 48}.
     */
    @NotBlank(message = "security.file-key.secret must be set: a random string of at least 32 characters, "
            + "the same on every instance. Generate one with: openssl rand -base64 48")
    @Size(min = 32, message = "security.file-key.secret is too short: use at least 32 characters. "
            + "Generate one with: openssl rand -base64 48")
    private String secret;
}
