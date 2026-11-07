package io.softa.starter.message.shared;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.orm.changelog.ChangeLogPublisher;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.jdbc.JdbcProxy;
import io.softa.framework.orm.jdbc.JdbcServiceImpl;
import io.softa.framework.orm.jdbc.database.SqlParams;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.PermissionService;
import io.softa.framework.orm.service.impl.ModelServiceImpl;
import io.softa.framework.orm.service.versioning.IdentityStrategy;
import io.softa.framework.orm.service.versioning.VersioningStrategyResolver;
import io.softa.starter.message.mail.enums.MailSendStatus;
import io.softa.starter.message.mail.service.impl.MailSendRecordServiceImpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * The send-record transitions are compare-and-sets on the framework's optimistic lock:
 * a caller passes the version it read, and of two callers holding the same version only one wins.
 *
 * <p>The services' other tests stub ModelService, so they cannot tell whether the lock holds — and
 * for a long time it did not: the framework dropped the readonly {@code version} from every update,
 * so both claims won and a mail went out twice. These run the real update path (ModelService, the
 * pipeline, the JDBC service and its SQL) over a simulated table that honours the version
 * condition; only the metadata is stubbed.
 */
class VersionLockCasTest {

    private final FakeTable table = new FakeTable();
    private final ModelServiceImpl<Long> modelService = new ModelServiceImpl<>();
    private MockedStatic<ModelManager> models;
    private SystemConfig previousConfig;

    @BeforeEach
    void setUp() {
        previousConfig = SystemConfig.env;
        SystemConfig.env = new SystemConfig();
        JdbcProxy jdbcProxy = mock(JdbcProxy.class);
        when(jdbcProxy.queryForList(anyString(), any())).thenAnswer(invocation -> table.select(invocation.getArgument(1)));
        when(jdbcProxy.update(anyString(), any())).thenAnswer(invocation -> table.update(invocation.getArgument(1)));
        JdbcServiceImpl<Long> jdbcService = new JdbcServiceImpl<>();
        ReflectionTestUtils.setField(jdbcService, "jdbcProxy", jdbcProxy);
        ReflectionTestUtils.setField(jdbcService, "changeLogPublisher", mock(ChangeLogPublisher.class));
        VersioningStrategyResolver versioning = mock(VersioningStrategyResolver.class);
        when(versioning.of(anyString())).thenReturn(new IdentityStrategy<>(jdbcService));
        ReflectionTestUtils.setField(modelService, "jdbcService", jdbcService);
        ReflectionTestUtils.setField(modelService, "permissionService", mock(PermissionService.class));
        ReflectionTestUtils.setField(modelService, "versioning", versioning);

        models = mockStatic(ModelManager.class);
        stubModel("MailSendRecord", "mail_send_record", Map.of(
                "status", FieldType.OPTION, "attemptStartedAt", FieldType.DATE_TIME, "retryCount", FieldType.INTEGER,
                "errorCode", FieldType.STRING, "errorMessage", FieldType.STRING, "nextRetryAt", FieldType.DATE_TIME));
    }

    @AfterEach
    void tearDown() {
        models.close();
        SystemConfig.env = previousConfig;
    }

    @Test
    void ofTwoWorkersClaimingTheSameMailOnlyOneSendsIt() {
        // The claim also stamps attemptStartedAt, so the second claim always differs from the first:
        // only the version can tell them apart.
        MailSendRecordServiceImpl mails = wire(new MailSendRecordServiceImpl());
        table.insert("mail_send_record", 1L, Map.of("status", MailSendStatus.PENDING.getCode()));

        assertThat(mails.markSending(1L, 0)).isTrue();
        assertThat(mails.markSending(1L, 0)).isFalse();
    }

    @Test
    void aClaimThatLosesTheRaceBetweenItsReadAndItsWriteLoses() {
        // Both workers read version 0; the other one commits after this one's read.
        MailSendRecordServiceImpl mails = wire(new MailSendRecordServiceImpl());
        table.insert("mail_send_record", 1L, Map.of("status", MailSendStatus.PENDING.getCode()));
        table.moveOnAfterNextRead(1L);

        assertThat(mails.markSending(1L, 0)).isFalse();
        assertThat(table.version(1L)).isEqualTo(1L);
    }

    @Test
    void aReviveHoldingAStaleVersionCannotUndoAClaim() {
        // ZombieRecordSweeper read the record at version 0; the delivery loop claimed it since.
        MailSendRecordServiceImpl mails = wire(new MailSendRecordServiceImpl());
        table.insert("mail_send_record", 1L, Map.of("status", MailSendStatus.PENDING.getCode(), "retryCount", 0));
        assertThat(mails.markSending(1L, 0)).isTrue();

        assertThat(mails.markRetry(1L, 0, "ZOMBIE_RECOVERED", "stale sweep", LocalDateTime.now())).isFalse();
        assertThat(table.row(1L)).containsEntry("status", MailSendStatus.SENDING.getCode());
    }

    private <S> S wire(S service) {
        ReflectionTestUtils.setField(service, "modelService", modelService);
        return service;
    }

    private void stubModel(String model, String table, Map<String, FieldType> businessFields) {
        MetaModel metaModel = new MetaModel();
        ReflectionTestUtils.setField(metaModel, "modelName", model);
        ReflectionTestUtils.setField(metaModel, "tableName", table);
        ReflectionTestUtils.setField(metaModel, "auditUpdateFields", Set.of(ModelConstant.UPDATED_TIME));
        models.when(() -> ModelManager.getModel(model)).thenReturn(metaModel);
        models.when(() -> ModelManager.getModelPrimaryKey(model)).thenReturn(ModelConstant.ID);
        models.when(() -> ModelManager.isVersionControl(model)).thenReturn(true);
        List<MetaField> fields = new ArrayList<>();
        fields.add(field(model, ModelConstant.ID, FieldType.LONG, true));
        fields.add(field(model, ModelConstant.VERSION, FieldType.LONG, true));
        fields.add(field(model, ModelConstant.UPDATED_TIME, FieldType.DATE_TIME, true));
        businessFields.forEach((name, type) -> fields.add(field(model, name, type, false)));
        fields.forEach(f -> models.when(() -> ModelManager.getModelField(model, f.getFieldName())).thenReturn(f));
        models.when(() -> ModelManager.getModelPrimaryKeyField(model)).thenReturn(fields.getFirst());
        models.when(() -> ModelManager.getModelFields(model)).thenReturn(fields);
        models.when(() -> ModelManager.getModelUpdatableFields(model)).thenAnswer(invocation -> new HashSet<>(
                fields.stream().filter(f -> !f.isReadonly()).map(MetaField::getFieldName).toList()));
    }

    private static MetaField field(String model, String name, FieldType type, boolean readonly) {
        MetaField field = new MetaField();
        ReflectionTestUtils.setField(field, "modelName", model);
        ReflectionTestUtils.setField(field, "fieldName", name);
        ReflectionTestUtils.setField(field, "columnName", name.replaceAll("([A-Z])", "_$1").toLowerCase());
        ReflectionTestUtils.setField(field, "fieldType", type);
        ReflectionTestUtils.setField(field, "readonly", readonly);
        return field;
    }

    /**
     * One row per id, read back by field name, written by the UPDATEs the framework sends: the SET
     * columns take their values, {@code version=version+1} moves the version on, and
     * {@code AND version = ?} makes a row at another version match nothing.
     */
    private static final class FakeTable {

        private final Map<Long, Map<String, Object>> rows = new HashMap<>();
        private Long moveOnAfterRead;

        void insert(String table, long id, Map<String, Object> values) {
            Map<String, Object> row = new HashMap<>(values);
            row.put(ModelConstant.ID, id);
            row.put(ModelConstant.VERSION, 0L);
            rows.put(id, row);
        }

        Map<String, Object> row(long id) {
            return rows.get(id);
        }

        long version(long id) {
            return (Long) rows.get(id).get(ModelConstant.VERSION);
        }

        /** Another writer commits right after the next read of this row. */
        void moveOnAfterNextRead(long id) {
            moveOnAfterRead = id;
        }

        List<Map<String, Object>> select(SqlParams sqlParams) {
            List<Map<String, Object>> result = new ArrayList<>();
            sqlParams.getArgs().forEach(id -> {
                if (rows.containsKey(id)) {
                    result.add(new HashMap<>(rows.get(id)));
                }
            });
            if (moveOnAfterRead != null) {
                rows.get(moveOnAfterRead).merge(ModelConstant.VERSION, 1L, (a, b) -> (Long) a + (Long) b);
                moveOnAfterRead = null;
            }
            return result;
        }

        int update(SqlParams sqlParams) {
            String sql = sqlParams.getSql();
            List<Object> args = sqlParams.getArgs();
            boolean guarded = sql.endsWith(" AND version = ?");
            Map<String, Object> row = rows.get((Long) args.get(guarded ? args.size() - 2 : args.size() - 1));
            if (row == null || guarded && !row.get(ModelConstant.VERSION).equals(((Number) args.getLast()).longValue())) {
                return 0;
            }
            String[] assignments = sql.substring(sql.indexOf(" SET ") + 5, sql.indexOf(" WHERE ")).split(",");
            int arg = 0;
            for (String assignment : assignments) {
                if (assignment.equals("version=version+1")) {
                    row.merge(ModelConstant.VERSION, 1L, (a, b) -> (Long) a + (Long) b);
                } else {
                    String column = assignment.substring(0, assignment.indexOf('='));
                    String field = java.util.regex.Pattern.compile("_([a-z])").matcher(column)
                            .replaceAll(match -> match.group(1).toUpperCase());
                    row.put(field, args.get(arg++));
                }
            }
            return 1;
        }
    }
}
