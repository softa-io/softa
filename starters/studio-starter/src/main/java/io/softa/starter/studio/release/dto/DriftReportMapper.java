package io.softa.starter.studio.release.dto;

import java.util.ArrayList;
import java.util.List;

import io.softa.starter.studio.release.enums.DriftKind;

/**
 * Translate the deploy-oriented {@link ModelChangesDTO} graph into the drift-oriented
 * {@link DriftReportDTO} list served by the read-only drift API.
 * <p>
 * Mapping table — note how the deploy-direction labels flip when read as drift:
 * <ul>
 *   <li>{@code createdRows} (snapshot has, runtime doesn't) → {@link DriftKind#RUNTIME_DELETED}</li>
 *   <li>{@code updatedRows} (matched, fields differ)        → {@link DriftKind#RUNTIME_MODIFIED}</li>
 *   <li>{@code deletedRows} (runtime has, snapshot doesn't) → {@link DriftKind#RUNTIME_ADDED}</li>
 * </ul>
 * For RUNTIME_MODIFIED, {@code dataAfterChange} (snapshot side per
 * {@code DesignAppEnvServiceImpl.diffSnapshotVsRuntime}) becomes {@code expected} and
 * {@code dataBeforeChange} (runtime side) becomes {@code actual}; only the diverged
 * fields are carried, never the full row.
 */
public final class DriftReportMapper {

    private DriftReportMapper() {}

    public static List<DriftReportDTO> toReport(List<ModelChangesDTO> drift) {
        if (drift == null || drift.isEmpty()) {
            return List.of();
        }
        List<DriftReportDTO> reports = new ArrayList<>(drift.size());
        for (ModelChangesDTO modelChanges : drift) {
            String model = modelChanges.getModelName();
            List<DriftRowDTO> rows = new ArrayList<>(
                    modelChanges.getCreatedRows().size()
                            + modelChanges.getUpdatedRows().size()
                            + modelChanges.getDeletedRows().size());

            for (RowChangeDTO row : modelChanges.getCreatedRows()) {
                rows.add(DriftRowDTO.builder()
                        .model(model)
                        .rowId(row.getRowId())
                        .kind(DriftKind.RUNTIME_DELETED)
                        .expected(row.getCurrentData())
                        .actual(null)
                        .changedFields(null)
                        .lastChangedTime(row.getLastChangedTime())
                        .build());
            }
            for (RowChangeDTO row : modelChanges.getUpdatedRows()) {
                rows.add(DriftRowDTO.builder()
                        .model(model)
                        .rowId(row.getRowId())
                        .kind(DriftKind.RUNTIME_MODIFIED)
                        .expected(row.getDataAfterChange())
                        .actual(row.getDataBeforeChange())
                        .changedFields(row.getDataBeforeChange() == null
                                ? null : row.getDataBeforeChange().keySet())
                        .lastChangedTime(row.getLastChangedTime())
                        .build());
            }
            for (RowChangeDTO row : modelChanges.getDeletedRows()) {
                rows.add(DriftRowDTO.builder()
                        .model(model)
                        .rowId(row.getRowId())
                        .kind(DriftKind.RUNTIME_ADDED)
                        .expected(null)
                        .actual(row.getCurrentData())
                        .changedFields(null)
                        .lastChangedTime(row.getLastChangedTime())
                        .build());
            }

            reports.add(DriftReportDTO.builder().model(model).rows(rows).build());
        }
        return reports;
    }
}
