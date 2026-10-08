package io.softa.starter.file.excel.export.support;

import java.util.List;
import java.util.Map;

/**
 * A rule every export of a model goes through, whichever way it is exported — dynamic, by field
 * template or by file template — after the rows are read and before they are written.
 *
 * <p>For what belongs to the model rather than to one template: a value that must not leave the
 * system under some condition of the row, say. {@link CustomExportHandler} is named by a template and
 * runs only for it; this runs for every export, including the dynamic one, which has no template to
 * name a handler in.
 *
 * <p>Implementations mutate the row maps in place and must not replace them. The rows carry
 * {@code id} even when it was not picked as a column.
 */
public interface ExportRowProcessor {

    /** Whether this processor applies to exports of {@code modelName}. */
    boolean supports(String modelName);

    /**
     * Process the rows of one export.
     *
     * @param modelName the exported model
     * @param rows      the rows, as read
     */
    void process(String modelName, List<Map<String, Object>> rows);
}
