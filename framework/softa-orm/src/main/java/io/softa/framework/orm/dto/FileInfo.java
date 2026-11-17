package io.softa.framework.orm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import io.softa.framework.orm.enums.FileType;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "FileInfo")
public class FileInfo {

    @Schema(description = "File ID", type = "string")
    private Long fileId;

    @Schema(description = "File Name")
    private String fileName;

    @Schema(description = "File Type")
    private FileType fileType;

    @Schema(description = "Address to open the file at: a site-relative link carrying a signed key. "
            + "For a logged-in user it opens for that account only and lasts 30 to 31 days; otherwise "
            + "anyone holding it can open it for an hour. Append ?download=1 to download instead of "
            + "displaying inline. Never stored: generated each time the file is returned.")
    private String url;

    @Schema(description = "File Size (KB)")
    private Integer size;

    @Schema(description = "Checksum")
    private String checksum;

}
