package io.softa.framework.orm.jdbc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

import io.softa.framework.base.utils.SpringContextUtils;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.FileService;

/**
 * Binds uploaded files to the row that references them, once that row has an id.
 *
 * <p>An upload from a create form happens before the record exists — {@code uploadFileToField} takes a
 * null {@code rowId} for exactly that reason — and nothing used to close the gap afterwards. The
 * {@code FileRecord} therefore kept {@code rowId = null} for its whole life, and a file could not say
 * which row owned it. Access to a file is meant to derive from access to that row, so without this the
 * derivation has nothing to stand on: every attachment looks ownerless, and a check written against the
 * owning row can only fall back to "whoever uploaded it" — which is precisely the wrong answer when the
 * employee uploads and HR needs to read.
 *
 * <p>Runs on create and on update alike, from the point where the id is known. Update matters as much as
 * create: replacing a file swaps in an id whose record still points at nothing.
 *
 * <p><b>Coverage — and its one boundary.</b> The backfill hangs off {@code JdbcServiceImpl.insertList}
 * and {@code updateList}, so it reaches every write that flows through them: everything through
 * {@code ModelService} (create / update / import / timeline / swapping an attachment) funnels there, and
 * both {@code FILE} and {@code MULTI_FILE} are handled (the latter split on commas, the shape
 * {@code StringProcessor} stores). What it does <b>not</b> reach is a write that bypasses that layer —
 * {@code JdbcServiceImpl.updateOne} issuing raw SQL, or anything hand-rolled straight onto
 * {@code jdbcProxy}. Those are framework-internal plumbing and must not be carrying a business File
 * field in the first place, so the gap is the correct boundary rather than a hole. <b>A new write path
 * that carries File fields must go through {@code insertList} / {@code updateList} to be bound</b> — or
 * call {@code FileService.claimFiles} itself.
 *
 * <p>Copy is deliberately outside all of this: File / MultiFile are non-copyable
 * ({@code ModelManager.getModelCopyableFields}), so a copied row carries no file id to rebind — which is
 * what stops a copy from re-pointing the original's file at itself.
 */
public class FileOwnership {

    private FileOwnership() {}

    /**
     * Claim every file referenced by these rows for the row that references it.
     *
     * <p>Best-effort by construction: a row with no file fields, a null value, or an unparseable id
     * contributes no claim. The write itself is one batched call — see
     * {@code FileServiceImpl#claimFiles}.
     *
     * @param modelName the model the rows belong to
     * @param rows rows that have already been written, each carrying its id
     */
    public static void claim(String modelName, List<Map<String, Object>> rows) {
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
        List<FileService.FileClaim> claims = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object rowId = row.get(ModelConstant.ID);
            if (rowId == null) {
                continue;
            }
            for (MetaField fileField : fileFields) {
                collectClaims(claims, row, fileField, modelName, rowId.toString());
            }
        }
        if (!claims.isEmpty()) {
            SpringContextUtils.getBeanByClass(FileService.class).claimFiles(claims);
        }
    }

    /** A FILE field holds one id; a MULTI_FILE field holds them comma-joined, as StringProcessor stores it. */
    private static void collectClaims(List<FileService.FileClaim> claims, Map<String, Object> row,
                                      MetaField fileField, String modelName, String rowId) {
        Object value = row.get(fileField.getFieldName());
        if (value == null) {
            return;
        }
        if (FieldType.FILE.equals(fileField.getFieldType())) {
            addClaim(claims, value, modelName, rowId, fileField.getFieldName());
            return;
        }
        for (String fileId : StringUtils.split(value.toString(), ",")) {
            addClaim(claims, fileId, modelName, rowId, fileField.getFieldName());
        }
    }

    private static void addClaim(List<FileService.FileClaim> claims, Object fileId,
                                 String modelName, String rowId, String fieldName) {
        String text = StringUtils.trimToNull(String.valueOf(fileId));
        if (text == null) {
            return;
        }
        try {
            claims.add(new FileService.FileClaim(Long.valueOf(text), modelName, rowId, fieldName));
        } catch (NumberFormatException e) {
            // Not a file id — nothing to bind. The field's own type handling is what rejects bad input;
            // failing a business write here would be the wrong place to enforce it.
        }
    }
}
