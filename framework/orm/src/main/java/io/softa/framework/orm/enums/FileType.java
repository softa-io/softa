package io.softa.framework.orm.enums;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import com.google.common.collect.Sets;
import lombok.AllArgsConstructor;
import lombok.Getter;

import io.softa.framework.base.utils.Assert;

/**
 * File type Enum.
 * Image types: JPG, PNG, SVG, GIF, ICO
 * Document types: CSV, TXT, DOC, DOCX, PPT, PPTX, XLS, XLSX, PDF
 * Code file types: JSON, XML
 */
@Getter
@AllArgsConstructor
public enum FileType {
    // Image types. jpg and jpeg are both `image/jpeg`
    JPG("jpg", List.of("image/jpeg", "image/jpg", "image/pjpeg")),
    PNG("png", List.of("image/png", "image/x-png", "application/png")),
    WEBP("webp", List.of("image/webp")),
    BMP("bmp", List.of("image/bmp")),
    TIF("tif", List.of("image/tiff")),
    SVG("svg", List.of("image/svg+xml")),
    GIF("gif", List.of("image/gif", "image/x-gif")),
    ICO("ico", List.of("image/vnd.microsoft.icon", "image/x-icon")),

    // Document types
    CSV("csv", List.of("text/csv")),
    TXT("txt", List.of("text/plain")),
    DOC("doc", List.of("application/msword")),
    DOCX("docx", List.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document")),
    PPT("ppt", List.of("application/vnd.ms-powerpoint")),
    PPTX("pptx", List.of("application/vnd.openxmlformats-officedocument.presentationml.presentation")),
    XLS("xls", List.of("application/vnd.ms-excel")),
    XLSX("xlsx", List.of("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")),
    PDF("pdf", List.of("application/pdf", "application/x-pdf")),

    // Code file types
    JSON("json", List.of("application/json", "text/json")),
    XML("xml", List.of("text/xml", "application/xml")),
    YAML("yaml", List.of("application/x-yaml", "text/yaml")),
    MD("md", List.of("text/markdown", "text/x-markdown", "text/x-web-markdown")),

    // Compressed file types
    ZIP("zip", List.of("application/zip", "application/x-zip-compressed")),
    GZIP("gzip", List.of("application/gzip", "application/x-gzip")),
    TAR("tar", List.of("application/x-tar", "application/x-gtar")),
    RAR("rar", List.of("application/vnd.rar", "application/x-rar-compressed")),

    // Audio types
    MP3("mp3", List.of("audio/mpeg")),
    WAV("wav", List.of("audio/x-wav", "audio/wav")),
    AAC("aac", List.of("audio/aac")),
    OGG("ogg", List.of("audio/ogg")),
    FLAC("flac", List.of("audio/flac")),

    // Video types
    MP4("mp4", List.of("video/mp4")),
     AVI("avi", List.of("video/x-msvideo")),
    MOV("mov", List.of("video/quicktime")),
    WMV("wmv", List.of("video/x-ms-wmv")),
    FLV("flv", List.of("video/x-flv")),
    ;

    private final String type;
    // Compatible mime types
    private final List<String> mimeTypeList;

    /** type map */
    static private final Map<String, FileType> typeMap = Stream.of(values()).collect(Collectors.toMap(FileType::getType, Function.identity()));

    /** mimetype map */
    static private final Map<String, FileType> mimeTypeMap = Stream.of(values())
            .flatMap(fileType -> fileType.getMimeTypeList().stream()
                    .map(mimeType -> Map.entry(mimeType, fileType)))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

    // Compatible image types
    public static final Set<FileType> COMPATIBLE_IMAGE_TYPE = Sets.newHashSet(JPG, PNG, WEBP, BMP);

    // Compatible text types
    public static final Set<FileType> COMPATIBLE_TEXT_TYPE = Sets.newHashSet(CSV, TXT, JSON, XML, YAML, SVG);

    // Image types
    public static final Set<FileType> IMAGE_TYPE = Sets.newHashSet(JPG, PNG, WEBP, BMP, TIF, SVG, GIF, ICO);

    // Document types
    public static final Set<FileType> DOCUMENT_TYPE = Sets.newHashSet(CSV, TXT, DOC, DOCX, PPT, PPTX, XLS, XLSX, PDF);

    // Code file types
    public static final Set<FileType> CODE_TYPE = Sets.newHashSet(JSON, XML, YAML);

    /**
     * Get file type by mimeType.
     *
     * @param mimetype the mimeType of the file
     * @return Optional<FileType>
     */
    public static Optional<FileType> of(String mimetype) {
        Assert.notNull(mimetype, "Cannot process files with empty Mimetype attribute!", mimetype);
        return Optional.ofNullable(mimeTypeMap.get(mimetype));
    }

    /**
     * Get file type by file extension.
     *
     * @param extension the file extension
     * @return Optional<FileType>
     */
    public static Optional<FileType> ofExtension(String extension) {
        return Optional.ofNullable(typeMap.get(extension));
    }

    /**
     * Get the file extension, `.` is included.
     *
     * @return the file extension
     */
    public String getExtension() {
        return "." + type;
    }
}
