package io.softa.starter.flow.service.support;

import java.util.Collection;
import java.util.List;

/**
 * Field definitions in the shape the metadata API serves a model's fields ({@code MetaFieldDTO} of
 * {@code GET /metadata/getMetaModel}), for a response that shows fields the caller's own metadata leaves out:
 * an approval's record read hands them out with the record, and the client merges them into the models it
 * loaded. {@link MetadataFieldDefinitions} answers with metadata-starter's own mapping; an application without
 * metadata-starter serves no metadata to merge into and registers none.
 */
@FunctionalInterface
public interface RecordFieldDefinitions {

    /**
     * The definitions of the named fields of a model, in the order named; a name the model does not have is
     * skipped.
     *
     * @param modelName  the model
     * @param fieldNames the fields
     * @return one definition per field found
     */
    List<Object> definitionsOf(String modelName, Collection<String> fieldNames);
}
