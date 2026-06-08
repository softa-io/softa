package io.softa.starter.file.vo;

import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

import io.softa.framework.orm.utils.FileUtils;
import io.softa.starter.file.dto.ImportFieldDTO;
import io.softa.starter.file.enums.ImportRule;

@Data
@Schema(name = "ImportWizard")
public class ImportWizard {

    @Schema(description = "Model name")
    private String modelName;

    @Schema(description = "Uploaded file")
    private MultipartFile file;

    @Schema(description = "Uploaded file name", hidden = true)
    private String fileName;

    @Schema(description = "Import Rule")
    private ImportRule importRule;

    @Schema(description = "Number of header rows", defaultValue = "1")
    private int headerRows = 1;

    @Schema(description = "Unique Constraints")
    private String uniqueConstraints;

    @Schema(description = """
            Import fields info. e.g.
                [{"header": "Product Code", "fieldName": "productCode", "required": true},
                 {"header": "Product Name", "fieldName": "productName", "required": true}]""")
    private List<ImportFieldDTO> importFieldDTOList;

    @Schema(description = "Whether to ignore empty values")
    private Boolean ignoreEmpty;

    @Schema(description = "Whether to continue importing next row data when encountering error.")
    private Boolean skipException;

    @Schema(description = "Custom Handler")
    private String customHandler;

    @Schema(description = "Synchronous Import")
    private Boolean syncImport;

    /**
     * Set the uploaded file and extract the file name.
     *
     * @param file the uploaded file
     */
    public void setFile(MultipartFile file) {
        this.file = file;
        this.fileName = FileUtils.getShortFileName(file);
    }
}
