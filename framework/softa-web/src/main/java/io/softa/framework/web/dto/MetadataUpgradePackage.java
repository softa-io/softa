package io.softa.framework.web.dto;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Metadata upgrade package
 */
@Data
@NoArgsConstructor
public class MetadataUpgradePackage implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String modelName;

    private List<Map<String, Object>> createRows = new ArrayList<>();

    private List<Map<String, Object>> updateRows = new ArrayList<>();

    private List<Long> deleteIds = new ArrayList<>();
}
