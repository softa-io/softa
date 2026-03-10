package io.softa.framework.web.dto;

import java.util.List;
import java.util.Map;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(name = "OnChangeParams")
public class OnChangeParams {
    private Map<String, Object> data;

    private List<String> update;
}
