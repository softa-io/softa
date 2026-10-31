package io.softa.framework.orm.service;

import java.io.InputStream;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.web.multipart.MultipartFile;

import io.softa.framework.orm.dto.FileInfo;
import io.softa.framework.orm.dto.UploadFileDTO;
import io.softa.framework.orm.entity.FileRecord;

public interface FileService {

    /**
     * Upload a file to the OSS and create a corresponding FileRecord to associate
     * with a business model and rowId.
     *
     * @param modelName the name of the corresponding business model
     * @param file      the file to be uploaded
     * @return fileId
     */
    Long uploadFile(String modelName, MultipartFile file);

    /**
     * Upload a file to the OSS and create a corresponding FileRecord to associate
     * with a business model and rowId.
     *
     * @param modelName the name of the corresponding business model
     * @param rowId     the ID of the corresponding business row data
     * @param fieldName The field name of the file belongs to
     * @param file      the file to be uploaded
     * @return fileInfo object
     */
    FileInfo uploadFile(String modelName, Serializable rowId, String fieldName, MultipartFile file);

    /**
     * Upload a file from URL to the OSS and create a corresponding FileRecord to associate
     * with a business model and rowId.
     *
     * @param modelName the name of the corresponding business model
     * @param rowId     the ID of the corresponding business row data
     * @param fieldName The field name of the file belongs to
     * @param url       the URL of the file
     * @param expireSeconds the expiration time in seconds
     * @return fileInfo object
     */
    FileInfo uploadFromUrl(String modelName, Serializable rowId, String fieldName, String url, int expireSeconds);

    /**
     * Upload a file to the OSS by input stream.
     * The uploadFileDTO contains the file information and input stream.
     *
     * @param uploadFileDTO the upload file DTO
     * @return filInfo object containing the download URL and metadata of the uploaded file
     */
    FileInfo uploadFromStream(UploadFileDTO uploadFileDTO);

    /**
     * Download the file stream from the OSS bucket by fileId
     *
     * @param fileId the ID of the file to be downloaded
     * @return the InputStream of the file
     */
    InputStream downloadStream(Long fileId);

    /**
     * Get the FileInfo object by fileId
     *
     * @param fileId the ID of the file
     * @return Optional object containing the FileInfo object if found, or empty if not found
     */
    Optional<FileInfo> getByFileId(Long fileId);

    /**
     * Get the FileInfo object by fileId
     *
     * @param fileId the ID of the file
     * @param expireSeconds the expiration time in seconds
     * @return Optional object containing the FileInfo object if found, or empty if not found
     */
    Optional<FileInfo> getByFileId(Long fileId, int expireSeconds);

    /**
     * Get the FileInfo object list by fileIds.
     *
     * @param fileIds the file IDs
     * @return the list of FileInfo objects
     */
    List<FileInfo> getByFileIds(List<Long> fileIds);


    /**
     * Reject a write that points a File field at a record it does not own.
     *
     * <p>The single place the "which row does this file belong to" question is enforced. Everything
     * downstream trusts the answer: a File column is expanded into a download URL with row-scope
     * waived, on the grounds that reading the row was already authorized — which only holds if the
     * id sitting in that column was put there legitimately. Nothing else validates it, so a caller
     * could otherwise write a stranger's file id into a row they may edit and read the file back
     * through the expansion.
     *
     * <p>Accepted:
     * <ul>
     *   <li><b>Unclaimed, same model</b> — the create-form path: the file was uploaded before the row
     *       existed. Same-model rather than same-uploader on purpose, because uploader and saver are
     *       not always the same person (a candidate uploads during pre-boarding, HR saves the record).</li>
     *   <li><b>Already bound to this very row</b> — re-saving a record must not fail.</li>
     * </ul>
     *
     * <p>Everything else — bound to another row, uploaded against another model, uploaded by another
     * tenant, or no such record — throws. Silently dropping the value was considered and rejected: a
     * dropped attachment looks to the user exactly like a saved one until they come back for it.
     *
     * <p>Batch-shaped on purpose: one write hands over every File-field statement it carries, all ids
     * are fetched in a single read, and each statement is checked against it in memory — the ownership
     * cost of a write does not grow with its row count.
     *
     * @param modelName the model being written
     * @param statements one per (row, field) the write carries, each naming the ids the field is being
     *        set to; a row being created carries a null rowId (only the unclaimed case can pass)
     * @return the records the statements were verified against, keyed by file id — hand it to
     *         {@link #claimFiles} so the paired claim does not read them a second time
     */
    Map<Long, FileRecord> assertClaimable(String modelName, Collection<OwnershipStatement> statements);

    /**
     * Bind the files these rows now reference, and release the ones they no longer do.
     *
     * <p>A file uploaded from a create form has no row yet — {@code uploadFileToField} accepts a null
     * {@code rowId} precisely because the record is written afterwards — so the binding can only be
     * made once the row's id is known. Without it {@code rowId} stays null forever and
     * {@link #assertClaimable} has nothing to enforce ownership against: no file would ever be owned,
     * and every file would be claimable by anyone.
     *
     * <p>Releasing is the other half. A write that carried a File field is a complete statement about
     * that field, so a record still bound to it whose id is absent from the new value is no longer
     * referenced and its binding is cleared. Without this, removing an attachment left the record
     * pointing at the row, and everyone who could read the row kept reaching a file it no longer
     * showed — the file surviving its own removal. The binding is cleared, not the file.
     *
     * <p>Idempotent, and silent about ids it cannot find: a claim naming a file deleted between upload
     * and save is not worth failing a business write over.
     *
     * @param claims the bindings to apply; empty is a no-op
     * @param slots the (model, row, field) triples the write actually carried; empty skips the release
     *        half — an insert passes none, because its freshly minted row ids cannot be vacated slots
     * @param preloaded the records the paired {@link #assertClaimable} already read, keyed by id; ids
     *        outside it are read individually, so {@code Map.of()} is always safe
     */
    void claimFiles(Collection<FileClaim> claims, Collection<FileSlot> slots, Map<Long, FileRecord> preloaded);

    /**
     * Give a second row its own record of the same stored file.
     *
     * <p>For a business flow that copies a row carrying an attachment — pre-boarding becoming an
     * employee, a record duplicated — where the copy must genuinely hold the document rather than
     * borrow it. Access to a file derives from the row that claims it, and a claim names one row, so
     * two rows pointing at one record leaves the copy readable only through the original's
     * permissions. A record each is what makes each row's own check the answer.
     *
     * <p>The stored object is copied too, under a new key: {@code FileRecord.ossKey} is unique, one
     * record per stored object, so two records cannot share one. The copy is made on the storage side,
     * without reading the bytes through this process. Should the surrounding transaction roll back, the
     * copied object is left with no record — harmless, as nothing reads an object no record names.
     *
     * <p><b>Not</b> reachable from a claim. Copying on demand there would turn writing a stranger's
     * file id into a row you may edit — the theft {@code claimFiles} refuses — into a supported way of
     * getting a copy of their document. The caller here is code that has already read both sides and
     * established the copy is legitimate.
     *
     * @return the new file id, or empty when the source file does not exist
     */
    Optional<Long> copyFileTo(Long fileId, String modelName, Serializable rowId, String fieldName);

    /**
     * One File field's write statement: the ids a (row, field) slot is being set to.
     *
     * @param rowId the row being written, null on create (no id yet)
     * @param fieldName the File field carrying the ids
     * @param fileIds the ids the field is being set to; never empty — an emptied field is a release,
     *        which the claim side states through its slot, not a statement to verify
     */
    record OwnershipStatement(String rowId, String fieldName, Set<Long> fileIds) {}

    /**
     * One file's binding to the row and field that reference it.
     *
     * @param fileId the file being bound
     * @param modelName the model of the owning row
     * @param rowId the id of the owning row
     * @param fieldName the field on that row holding this file
     */
    record FileClaim(Long fileId, String modelName, String rowId, String fieldName) {}

    /**
     * A (model, row, field) triple a write carried — the unit a release is scoped to.
     *
     * @param modelName the model written
     * @param rowId the row written
     * @param fieldName the File field the write carried
     */
    record FileSlot(String modelName, String rowId, String fieldName) {}

}
