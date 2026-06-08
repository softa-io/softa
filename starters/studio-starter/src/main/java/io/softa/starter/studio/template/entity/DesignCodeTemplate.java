package io.softa.starter.studio.template.entity;

import java.io.Serial;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.starter.studio.template.enums.DesignCodeLang;

/**
 * DesignCodeTemplate Model
 */
@Data
@Model(label = "Design Code Template", idStrategy = IdStrategy.DISTRIBUTED_LONG)
@EqualsAndHashCode(callSuper = true)
public class DesignCodeTemplate extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    @Field(label = "Code Lang")
    private DesignCodeLang codeLang;

    @Field(label = "Sequence")
    private Integer sequence;

    @Field(label = "Sub Directory", length = 64)
    private String subDirectory;

    @Field(label = "File Name", length = 64)
    private String fileName;

    @Field(label = "Template Content", length = 20000)
    private String templateContent;

    @Field(label = "Description", length = 256)
    private String description;

    @Field(label = "Deleted")
    private Boolean deleted;
}
