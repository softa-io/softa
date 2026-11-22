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
import io.softa.framework.base.annotation.OptionItem;
import io.softa.framework.base.annotation.OptionSet;

/**
 * File type Enum.
 * Image types: JPG, PNG, SVG, GIF, ICO
 * Document types: CSV, TXT, DOC, DOCX, PPT, PPTX, XLS, XLSX, PDF
 * Code file types: JSON, XML
 */
@Getter
@AllArgsConstructor
@OptionSet
public enum FileType {
    // Image types. jpg and jpeg are both `image/jpeg`
    @OptionItem(label = "JPG")
    JPG("jpg", List.of("image/jpeg", "image/jpg", "image/pjpeg")),
    @OptionItem(label = "PNG")
    PNG("png", List.of("image/png", "image/x-png", "application/png")),
    @OptionItem(label = "WebP")
    WEBP("webp", List.of("image/webp")),
    @OptionItem(label = "BMP")
    BMP("bmp", List.of("image/bmp")),
    @OptionItem(label = "TIFF")
    TIF("tif", List.of("image/tiff")),
    @OptionItem(label = "SVG")
    SVG("svg", List.of("image/svg+xml")),
    @OptionItem(label = "GIF")
    GIF("gif", List.of("image/gif", "image/x-gif")),
    @OptionItem(label = "ICO")
    ICO("ico", List.of("image/vnd.microsoft.icon", "image/x-icon")),

    // Document types
    @OptionItem(label = "CSV")
    CSV("csv", List.of("text/csv")),
    @OptionItem(label = "TXT")
    TXT("txt", List.of("text/plain")),
    @OptionItem(label = "DOC")
    DOC("doc", List.of("application/msword")),
    @OptionItem(label = "DOCX")
    DOCX("docx", List.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document")),
    @OptionItem(label = "PPT")
    PPT("ppt", List.of("application/vnd.ms-powerpoint")),
    @OptionItem(label = "PPTX")
    PPTX("pptx", List.of("application/vnd.openxmlformats-officedocument.presentationml.presentation")),
    @OptionItem(label = "XLS")
    XLS("xls", List.of("application/vnd.ms-excel")),
    @OptionItem(label = "XLSX")
    XLSX("xlsx", List.of("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")),
    @OptionItem(label = "PDF")
    PDF("pdf", List.of("application/pdf", "application/x-pdf")),

    // Code file types
    @OptionItem(label = "JSON")
    JSON("json", List.of("application/json", "text/json")),
    @OptionItem(label = "XML")
    XML("xml", List.of("text/xml", "application/xml")),
    @OptionItem(label = "YAML")
    YAML("yaml", List.of("application/x-yaml", "text/yaml")),
    @OptionItem(label = "Markdown")
    MD("md", List.of("text/markdown", "text/x-markdown", "text/x-web-markdown")),

    // Email types
    @OptionItem(label = "EML")
    EML("eml", List.of("message/rfc822")),

    // Compressed file types
    @OptionItem(label = "ZIP")
    ZIP("zip", List.of("application/zip", "application/x-zip-compressed")),
    @OptionItem(label = "GZIP")
    GZIP("gzip", List.of("application/gzip", "application/x-gzip")),
    @OptionItem(label = "TAR")
    TAR("tar", List.of("application/x-tar", "application/x-gtar")),
    @OptionItem(label = "RAR")
    RAR("rar", List.of("application/vnd.rar", "application/x-rar-compressed")),

    // Audio types
    @OptionItem(label = "MP3")
    MP3("mp3", List.of("audio/mpeg")),
    @OptionItem(label = "WAV")
    WAV("wav", List.of("audio/x-wav", "audio/wav")),
    @OptionItem(label = "AAC")
    AAC("aac", List.of("audio/aac")),
    @OptionItem(label = "OGG")
    OGG("ogg", List.of("audio/ogg")),
    @OptionItem(label = "FLAC")
    FLAC("flac", List.of("audio/flac")),

    // Video types
    @OptionItem(label = "MP4")
    MP4("mp4", List.of("video/mp4")),
    @OptionItem(label = "AVI")
     AVI("avi", List.of("video/x-msvideo")),
    @OptionItem(label = "MOV")
    MOV("mov", List.of("video/quicktime")),
    @OptionItem(label = "WMV")
    WMV("wmv", List.of("video/x-ms-wmv")),
    @OptionItem(label = "FLV")
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

    // Compatible text types. Not SVG: an image that can carry script is accepted as SVG only, never
    // under a text file's name or type.
    public static final Set<FileType> COMPATIBLE_TEXT_TYPE = Sets.newHashSet(CSV, TXT, JSON, XML, YAML);

    // Image types
    public static final Set<FileType> IMAGE_TYPE = Sets.newHashSet(JPG, PNG, WEBP, BMP, TIF, SVG, GIF, ICO);

    /**
     * Types a browser runs scripts in when it opens the file as a page: an SVG or XML document can
     * carry a script element or an event handler. Their storage links are always downloads
     * ({@code FileService.getStorageLink}); an img element still shows an SVG, since
     * Content-Disposition only applies to opening a page.
     */
    public static final Set<FileType> SCRIPTABLE_TYPE = Sets.newHashSet(SVG, XML);

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
     * Get file type by the extension a file name or object key ends in, in any case; {@code jpeg}
     * reads as JPG.
     *
     * @param fileName a file name or object key
     * @return the type its extension names, or empty when it has none or one of no type here
     */
    public static Optional<FileType> ofFileName(String fileName) {
        if (fileName == null) {
            return Optional.empty();
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return Optional.empty();
        }
        String extension = fileName.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        return ofExtension("jpeg".equals(extension) ? JPG.getType() : extension);
    }

    /**
     * The Content-Type an object stored under this name should carry: the first mime type of the type
     * its extension names, or {@code application/octet-stream} when the extension is missing or not
     * one of these types. Stored without it, an object is served as octet-stream and a browser
     * downloads it even when asked only to display it.
     *
     * @param fileName a file name or object key ending in the extension
     * @return the Content-Type to store the object with
     */
    public static String contentTypeOf(String fileName) {
        return ofFileName(fileName)
                .map(FileType::getMimeTypeList)
                .filter(list -> !list.isEmpty())
                .map(List::getFirst)
                .orElse(OCTET_STREAM);
    }

    private static final String OCTET_STREAM = "application/octet-stream";

    /**
     * Get the file extension, `.` is included.
     *
     * @return the file extension
     */
    public String getExtension() {
        return "." + type;
    }
}
