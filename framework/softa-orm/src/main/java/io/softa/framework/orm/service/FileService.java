package io.softa.framework.orm.service;

import java.io.InputStream;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.web.multipart.MultipartFile;

import io.softa.framework.orm.dto.FileInfo;
import io.softa.framework.orm.dto.UploadFileDTO;

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
     * Get the FileInfo object by modelName and rowId
     *
     * @param modelName the name of the corresponding business model
     * @param rowId the ID of the corresponding business row data
     * @return fileInfo object with download URL
     */
    List<FileInfo> getRowFiles(String modelName, Serializable rowId);

    /**
     * Bind uploaded files to the row that now references them.
     *
     * <p>A file uploaded from a create form has no row yet — {@code uploadFileToField} accepts a null
     * {@code rowId} precisely because the record is written afterwards. Nothing used to close that gap,
     * so the {@code FileRecord} kept {@code rowId = null} forever, and a file could not say which row
     * owned it. That matters beyond tidiness: access to a file is meant to derive from access to the
     * row it hangs on, and a file with no row cannot be authorized that way.
     *
     * <p>Called by the ORM once the row's id is known, on create and on update alike. Idempotent, and
     * silent about ids it cannot find — a claim naming a deleted file is not worth failing a business
     * write over.
     *
     * @param claims the bindings to apply; empty is a no-op
     */
    void claimFiles(Collection<FileClaim> claims);

    /**
     * Bind the files these rows now reference, and release the ones they no longer do.
     *
     * <p>{@code slots} names the (model, row, field) triples the write actually carried. A field
     * present in the write is a complete statement about that field, so a file still claimed by it and
     * absent from {@code claims} is released back to unclaimed — clearing an attachment used to leave
     * the record pointing at the row, which kept the file listed by {@link #getRowFiles} and readable
     * by anyone who could read that row. A field the write never mentioned is not in {@code slots} and
     * is left untouched, which is what makes a partial update safe.
     *
     * @param claims the bindings to apply; may be empty when every carried field was cleared
     * @param slots the (model, row, field) triples this write spoke for; empty is a no-op
     */
    void claimFiles(Collection<FileClaim> claims, Collection<FileSlot> slots);

    /**
     * Who owns this file — the row it hangs on, or failing that the user who uploaded it.
     *
     * <p>Exists so a caller holding only a file id can authorize the read the way it should be
     * authorized: against the record the file belongs to. {@link FileInfo} deliberately does not carry
     * this — it is the response DTO, and ownership is not something every client should receive.
     *
     * @param fileId the file to resolve
     * @return the owner, or empty when no such file exists
     */
    Optional<FileOwner> getFileOwner(Long fileId);

    /**
     * Give a second row its own record of the same stored file.
     *
     * <p>For a business flow that copies a row carrying an attachment — pre-boarding becoming an
     * employee, a record duplicated — where the copy must genuinely hold the document rather than
     * borrow it. Access to a file derives from the row that claims it, and a claim names one row, so
     * two rows pointing at one record leaves the copy readable only through the original's
     * permissions. A record each is what makes each row's own check the answer.
     *
     * <p>The stored object is shared, not duplicated: nothing in this framework deletes from object
     * storage, so a second reference cannot be left dangling by the first going away. Anything that
     * adds deletion later has to look for other records on the same key first.
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
     * A file's owning row, plus its uploader for the case where no row claims it yet.
     *
     * @param modelName the model of the owning row, null while unclaimed
     * @param rowId the id of the owning row, null while unclaimed
     * @param fieldName the field on that row holding this file, null when the file hangs on the row
     *                  itself rather than on a column
     * @param uploaderId the user who uploaded it — the only defensible owner of an unclaimed file
     */
    record FileOwner(String modelName, String rowId, String fieldName, Long uploaderId) {

        /** Unclaimed: uploaded, not yet referenced by any row. Authorize against the uploader. */
        public boolean isUnclaimed() {
            return rowId == null || rowId.isBlank() || modelName == null || modelName.isBlank();
        }
    }

    /**
     * One file's binding to the row and field that reference it.
     *
     * @param fileId the file being claimed
     * @param modelName the model of the owning row
     * @param rowId the id of the owning row
     * @param fieldName the field on that row holding this file
     */
    record FileClaim(Long fileId, String modelName, String rowId, String fieldName) {}

    /**
     * One (model, row, field) triple a write spoke for — the unit a release is scoped to.
     *
     * @param modelName the model of the row written
     * @param rowId the id of the row written
     * @param fieldName the file field the write carried
     */
    record FileSlot(String modelName, String rowId, String fieldName) {}

}
