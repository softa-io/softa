package io.softa.framework.orm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import io.softa.framework.orm.enums.FileType;

@Slf4j
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DownloadFileDTO {

    @Schema(description = "Input Stream")
    private InputStream inputStream;

    @Schema(description = "File Name")
    private String fileName;

    @Schema(description = "File Type")
    private FileType fileType;

    @Schema(description = "File Size(KB)")
    private int fileSize;

    @Schema(description = "HTTP Connection")
    private HttpURLConnection connection;

    /**
     * Close the connection and input stream
     */
    public void close() {
        if (inputStream != null) {
            try {
                inputStream.close();
            } catch (IOException e) {
                log.warn("Failed to close input stream", e);
            }
        }
        if (connection != null) {
            connection.disconnect();
        }
    }
}
