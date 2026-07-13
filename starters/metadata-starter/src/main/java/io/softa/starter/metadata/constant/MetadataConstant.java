package io.softa.starter.metadata.constant;

public interface MetadataConstant {

    /** Servlet URL pattern (filter form) for the signed prefix. */
    String SIGNED_URL_PATTERN = "/upgrade/runtime/*";

    String METADATA_EXPORT_API = "/upgrade/runtime/exportRuntimeMetadata";
    String METADATA_CHECKSUMS_API = "/upgrade/runtime/exportRuntimeChecksums";
    String METADATA_APPLY_DESIRED_API = "/upgrade/runtime/applyDesiredAggregates";
}
