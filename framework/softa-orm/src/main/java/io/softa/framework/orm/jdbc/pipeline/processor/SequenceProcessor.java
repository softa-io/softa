package io.softa.framework.orm.jdbc.pipeline.processor;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;

import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.sequence.SequenceService;

/**
 * Field processor that fills a value from a sequence on INSERT.
 *
 * <p>Activation is gated by:
 * <ul>
 *   <li>{@code accessType == CREATE} (sequence allocation only happens on insert)</li>
 *   <li>{@code metaField.isAutoSequence() == true} (binding declared by sys_sequence row)</li>
 *   <li>The corresponding row's value is null/blank (caller-provided values are trusted)</li>
 * </ul>
 *
 * <p>The sequence code is reconstructed at construction time from
 * {@code modelName + "." + fieldName} per the v1 naming convention; the actual
 * lookup against {@code sys_sequence} happens inside {@link SequenceService}.
 *
 * <p>{@link #batchProcessInputRows(List)} is overridden to coalesce a batch
 * insert into a single {@code nextBatch} call, taking the row lock once for
 * the whole batch instead of {@code rows.size()} times.
 */
public class SequenceProcessor extends BaseProcessor {

    private final SequenceService sequenceService;
    /** Sequence code, pre-built from {@code modelName + "." + fieldName}. */
    private final String code;

    public SequenceProcessor(MetaField metaField, AccessType accessType, SequenceService sequenceService) {
        super(metaField, accessType);
        this.sequenceService = sequenceService;
        this.code = metaField.getModelName() + "." + metaField.getFieldName();
    }

    @Override
    public void processInputRow(Map<String, Object> row) {
        // Defensive: factory should have filtered, but guard anyway.
        if (!AccessType.CREATE.equals(accessType)) {
            return;
        }
        if (isBlank(row.get(fieldName))) {
            row.put(fieldName, sequenceService.next(code));
        }
    }

    /**
     * Batch fast-path: collect blank rows, allocate {@code blanks.size()} numbers
     * with a single {@code nextBatch} call, then assign them in order.
     */
    @Override
    public void batchProcessInputRows(List<Map<String, Object>> rows) {
        if (!AccessType.CREATE.equals(accessType)) {
            return;
        }
        List<Map<String, Object>> blanks = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            if (isBlank(row.get(fieldName))) {
                blanks.add(row);
            }
        }
        if (blanks.isEmpty()) {
            return;
        }
        if (blanks.size() == 1) {
            blanks.get(0).put(fieldName, sequenceService.next(code));
            return;
        }
        List<String> codes = sequenceService.nextBatch(code, blanks.size());
        Iterator<String> it = codes.iterator();
        for (Map<String, Object> r : blanks) {
            r.put(fieldName, it.next());
        }
    }

    private static boolean isBlank(Object v) {
        return v == null || (v instanceof CharSequence cs && StringUtils.isBlank(cs.toString()));
    }
}
