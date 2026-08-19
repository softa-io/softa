package io.softa.framework.orm.jdbc;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

import io.softa.framework.base.utils.SpringContextUtils;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.FileService;

/**
 * The two halves of file ownership, hung off the write path.
 *
 * <p>Access to a file derives from access to the row that holds it: a File column is expanded into a
 * download URL with FileRecord's own row-scope waived, because the read of the row was already
 * authorized. That reasoning has one load-bearing assumption — <b>the id in the column belongs to
 * that row</b> — and nothing in the ORM would otherwise establish it. A File field is an ordinary
 * column; a caller who may edit a row may write any number into it, including the id of someone
 * else's file, and the expansion would hand back its URL.
 *
 * <p>So the write path does both jobs, in order:
 *
 * <ol>
 *   <li>{@link #validate} runs <b>before</b> the row is written and rejects an id this row may not
 *       point at. This is what makes the column trustworthy, and therefore what makes the bypass on
 *       the read side sound.</li>
 *   <li>{@link #claim} runs <b>after</b>, once the id exists, and records the binding on the
 *       FileRecord — which is what step 1 consults next time. Without it no file is ever owned, every
 *       file looks unclaimed, and validation has nothing to enforce.</li>
 * </ol>
 *
 * <p>Neither half stands alone. They are one mechanism, split by the fact that a row's id does not
 * exist until it has been inserted.
 *
 * <p><b>Coverage, and its one boundary.</b> Both hang off {@code JdbcServiceImpl.insertList} and
 * {@code updateList}, so they reach every write that flows through them — everything via
 * {@code ModelService} (create / update / import / timeline / swapping an attachment) funnels there,
 * and {@code FILE} and {@code MULTI_FILE} are both handled (the latter split on commas, the shape
 * {@code StringProcessor} stores). What they do not reach is a write that bypasses that layer:
 * {@code updateOne} issuing raw SQL, or anything hand-rolled onto {@code jdbcProxy}. Those are
 * framework-internal plumbing and must not carry a business File field in the first place, so the gap
 * is the correct boundary rather than a hole. <b>A new write path carrying File fields must go through
 * {@code insertList} / {@code updateList}</b>, or call {@code FileService} itself.
 */
public class FileOwnership {

    private FileOwnership() {}

    /**
     * Reject a write pointing a File field at a file the row does not own. Throws before anything is
     * written, so a refused attachment fails the save rather than being silently dropped.
     *
     * @param modelName the model being written
     * @param rows the rows about to be written; on create they carry no id yet
     */
    public static void validate(String modelName, List<Map<String, Object>> rows) {
        forEachWrittenFileField(modelName, rows, (row, fileField, fileIds) -> {
            Object rowId = row.get(ModelConstant.ID);
            SpringContextUtils.getBeanByClass(FileService.class).assertClaimable(
                    modelName,
                    rowId == null ? null : rowId.toString(),
                    fileField.getFieldName(),
                    fileIds);
        });
    }

    /**
     * Bind every file these rows reference to the row referencing it, and release the ones they
     * stopped referencing.
     *
     * @param modelName the model the rows belong to
     * @param rows rows that have already been written, each carrying its id
     */
    public static void claim(String modelName, List<Map<String, Object>> rows) {
        List<FileService.FileClaim> claims = new ArrayList<>();
        List<FileService.FileSlot> writtenSlots = new ArrayList<>();
        forEachWrittenFileField(modelName, rows, (row, fileField, fileIds) -> {
            Object rowId = row.get(ModelConstant.ID);
            if (rowId == null) {
                return;
            }
            writtenSlots.add(new FileService.FileSlot(modelName, rowId.toString(), fileField.getFieldName()));
            for (Long fileId : fileIds) {
                claims.add(new FileService.FileClaim(fileId, modelName, rowId.toString(), fileField.getFieldName()));
            }
        });
        if (!writtenSlots.isEmpty()) {
            SpringContextUtils.getBeanByClass(FileService.class).claimFiles(claims, writtenSlots);
        }
    }

    /** What to do with one row's one File field and the ids it was set to. */
    @FunctionalInterface
    private interface FileFieldVisitor {
        void accept(Map<String, Object> row, MetaField fileField, Set<Long> fileIds);
    }

    /**
     * Walk the File fields each row's write actually carried.
     *
     * <p>Only a field present in the map is a statement about that field. A partial update that never
     * mentions it says nothing — visiting it anyway would validate ids nobody submitted and, on the
     * claim side, release every file the row still holds.
     */
    private static void forEachWrittenFileField(String modelName, List<Map<String, Object>> rows,
                                                FileFieldVisitor visitor) {
        if (CollectionUtils.isEmpty(rows)) {
            return;
        }
        List<MetaField> fileFields = ModelManager.getModelFields(modelName).stream()
                .filter(f -> FieldType.FILE.equals(f.getFieldType())
                        || FieldType.MULTI_FILE.equals(f.getFieldType()))
                .toList();
        if (fileFields.isEmpty()) {
            return;
        }
        for (Map<String, Object> row : rows) {
            for (MetaField fileField : fileFields) {
                if (!row.containsKey(fileField.getFieldName())) {
                    continue;
                }
                visitor.accept(row, fileField, parseIds(row.get(fileField.getFieldName()), fileField));
            }
        }
    }

    /** A FILE field holds one id; a MULTI_FILE field holds them comma-joined, as StringProcessor stores it. */
    private static Set<Long> parseIds(Object value, MetaField fileField) {
        Set<Long> ids = new LinkedHashSet<>();
        if (value == null) {
            return ids;
        }
        if (FieldType.FILE.equals(fileField.getFieldType())) {
            addId(ids, value);
            return ids;
        }
        if (value instanceof Iterable<?> many) {
            many.forEach(one -> addId(ids, one));
            return ids;
        }
        for (String fileId : StringUtils.split(value.toString(), ",")) {
            addId(ids, fileId);
        }
        return ids;
    }

    private static void addId(Set<Long> ids, Object fileId) {
        String text = fileId == null ? null : StringUtils.trimToNull(String.valueOf(fileId));
        if (text == null) {
            return;
        }
        try {
            ids.add(Long.valueOf(text));
        } catch (NumberFormatException e) {
            // Not a file id at all. The field's own type handling is what rejects malformed input;
            // treating it as an ownership failure here would report the wrong problem.
        }
    }
}
