package io.softa.starter.permission.spi.support;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.permission.spi.SensitiveFieldSetSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Standalone default {@link SensitiveFieldSetSource} — reads the RBAC
 * {@code SensitiveFieldSet} rows straight from the DB by model name (约定读, no
 * entity classes), so {@code permission-starter} can build its
 * {@code SensitiveFieldSetCache} with no {@code user-starter} on the classpath.
 * Registered only when no other source is present
 * ({@code @ConditionalOnMissingBean}); in the monolith {@code user-starter}'s
 * {@code SensitiveFieldSetSourceImpl} wins.
 *
 * <p>Queried once at {@code SensitiveFieldSetCache} {@code @PostConstruct} (not
 * per request), so the DB read is one-time at boot. Graceful degradation: when
 * the {@code SensitiveFieldSet} model isn't present it returns empty.
 */
@Slf4j
@RequiredArgsConstructor
public class DbSensitiveFieldSetSource implements SensitiveFieldSetSource {

    private static final String SFS_MODEL = "SensitiveFieldSet";
    private static final String F_ID = "id";
    private static final String F_MODEL = "model";
    private static final String F_NAME = "name";
    private static final String F_FIELD_CODES = "fieldCodes";
    private static final String F_ATTACHED_TO = "attachedTo";
    private static final String F_LABEL = "label";

    private final ModelService<?> modelService;

    @Override
    public List<SensitiveFieldSetDef> getSensitiveFieldSets() {
        if (!ModelManager.existModel(SFS_MODEL)) {
            log.debug("DbSensitiveFieldSetSource — model '{}' absent; no sensitive field sets", SFS_MODEL);
            return List.of();
        }
        List<Map<String, Object>> rows = modelService.searchList(
                SFS_MODEL, new FlexQuery(List.of(F_ID, F_MODEL, F_NAME, F_FIELD_CODES, F_ATTACHED_TO, F_LABEL)));
        List<SensitiveFieldSetDef> out = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            String id = asString(r.get(F_ID));
            if (id == null) continue;
            String model = asString(r.get(F_MODEL));
            String name = asString(r.get(F_NAME));
            Set<String> codes = toSet(JsonUtils.toStringList(r.get(F_FIELD_CODES)));
            Set<String> attached = toSet(JsonUtils.toStringList(r.get(F_ATTACHED_TO)));
            out.add(new SensitiveFieldSetDef(id, model, codes, name, attached, asString(r.get(F_LABEL))));
        }
        return out;
    }

    private static Set<String> toSet(List<String> l) {
        return l == null ? new HashSet<>() : new HashSet<>(l);
    }

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }
}
