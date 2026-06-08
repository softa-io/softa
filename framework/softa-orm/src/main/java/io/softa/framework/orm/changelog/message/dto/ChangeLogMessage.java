package io.softa.framework.orm.changelog.message.dto;

import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;

import io.softa.framework.base.context.Context;

/**
 * ChangeLog Message DTO
 */
@Data
@NoArgsConstructor
public class ChangeLogMessage {

    private List<ChangeLog> changeLogs;

    private Context context;

    public ChangeLogMessage(List<ChangeLog> changeLogs, Context context) {
        this.changeLogs = changeLogs;
        this.context = context;
    }
}
