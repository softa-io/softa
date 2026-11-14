package io.softa.starter.seed.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import io.softa.framework.base.security.HashUtils;
import io.softa.starter.seed.manifest.SeedFile;
import io.softa.starter.seed.manifest.SeedManifest;

/**
 * The seed files of the running build: the manifest, each file's checksum, and which build this is.
 */
@Component
public class SeedCatalog {

    private final SeedManifest manifest;
    private final String appVersion;
    private final LocalDateTime buildTime;
    /** When this instance started, shown beside the latest platform load. */
    private final LocalDateTime startedAt = LocalDateTime.now();
    /** Classpath location → SHA-256. The classpath does not change while the application runs. */
    private final Map<String, String> checksums = new ConcurrentHashMap<>();

    public SeedCatalog(SeedManifest manifest, ObjectProvider<BuildProperties> buildProperties) {
        this.manifest = manifest;
        BuildProperties build = buildProperties.getIfAvailable();
        this.appVersion = build == null ? null : build.getVersion();
        this.buildTime = build == null || build.getTime() == null
                ? null : LocalDateTime.ofInstant(build.getTime(), ZoneId.systemDefault());
    }

    public SeedManifest manifest() {
        return manifest;
    }

    /** SHA-256 of the file as the running build carries it, its line endings read as LF. */
    public String checksum(SeedFile file) {
        return checksums.computeIfAbsent(file.path(), SeedCatalog::sha256);
    }

    public String appVersion() {
        return appVersion;
    }

    public LocalDateTime buildTime() {
        return buildTime;
    }

    public LocalDateTime startedAt() {
        return startedAt;
    }

    static String sha256(String classpathLocation) {
        try (InputStream in = new ClassPathResource(classpathLocation).getInputStream()) {
            return HashUtils.sha256Hex(withLfLineEndings(in.readAllBytes()));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read seed file " + classpathLocation, e);
        }
    }

    /**
     * The bytes with every CRLF read as LF. A file checked out with CRLF line endings — a Windows checkout of a
     * repository without a {@code .gitattributes} rule — is the same file, but hashed raw it reads as changed:
     * a build from such a checkout would reload every platform file and re-run the overwrite tenant files in
     * every tenant. A file with LF line endings hashes as it always did. UTF-8 never carries CR or LF inside a
     * multi-byte character, so the bytes can be read one by one.
     */
    static byte[] withLfLineEndings(byte[] bytes) {
        ByteArrayOutputStream lf = new ByteArrayOutputStream(bytes.length);
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '\r' && i + 1 < bytes.length && bytes[i + 1] == '\n') {
                continue;
            }
            lf.write(bytes[i]);
        }
        return lf.toByteArray();
    }
}
