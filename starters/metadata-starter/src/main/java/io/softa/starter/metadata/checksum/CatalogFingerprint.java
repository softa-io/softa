package io.softa.starter.metadata.checksum;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import io.softa.framework.base.security.HashUtils;
import io.softa.starter.metadata.entity.SysField;
import io.softa.starter.metadata.entity.SysModel;
import io.softa.starter.metadata.entity.SysModelIndex;
import io.softa.starter.metadata.entity.SysOptionItem;
import io.softa.starter.metadata.entity.SysOptionSet;

/**
 * One whole-catalog fingerprint: SHA-256 over the sorted per-aggregate checksums
 * ({@link AggregateChecksumService} — the same canonical projection the studio↔runtime
 * checksum handshake uses, so two catalogs fingerprint equal iff their schema-relevant
 * state is equal). Input order never matters; a change to any allow-listed attribute of
 * any aggregate changes the fingerprint.
 *
 * <p>Both the annotation side (the scanner's / checker's parsed from-code catalog) and the
 * row side ({@code sys_*}) are fed through the identical projection, which is what makes the
 * two fingerprints comparable on {@code /metadata/status}.
 */
public final class CatalogFingerprint {

    private CatalogFingerprint() {
    }

    public static String of(List<SysModel> models, List<SysField> fields, List<SysModelIndex> indexes,
                            List<SysOptionSet> optionSets, List<SysOptionItem> optionItems) {
        AggregateChecksumService service = new AggregateChecksumService();
        Map<String, List<SysField>> fieldsByModel = fields.stream()
                .collect(Collectors.groupingBy(SysField::getModelName));
        Map<String, List<SysModelIndex>> indexesByModel = indexes.stream()
                .collect(Collectors.groupingBy(SysModelIndex::getModelName));
        Map<String, List<SysOptionItem>> itemsBySet = optionItems.stream()
                .collect(Collectors.groupingBy(SysOptionItem::getOptionSetCode));

        // TreeMap = deterministic aggregate order regardless of input order.
        TreeMap<String, String> aggregates = new TreeMap<>();
        for (SysModel model : models) {
            aggregates.put("model:" + model.getModelName(), service.modelChecksum(
                    model,
                    fieldsByModel.getOrDefault(model.getModelName(), List.of()),
                    indexesByModel.getOrDefault(model.getModelName(), List.of())));
        }
        for (SysOptionSet optionSet : optionSets) {
            aggregates.put("optionSet:" + optionSet.getOptionSetCode(), service.optionSetChecksum(
                    optionSet,
                    itemsBySet.getOrDefault(optionSet.getOptionSetCode(), List.of())));
        }
        StringBuilder canonical = new StringBuilder();
        aggregates.forEach((key, checksum) -> canonical.append(key).append('=').append(checksum).append('\n'));
        return HashUtils.sha256Hex(canonical.toString());
    }
}
