package io.softa.starter.flow.service.support;

import org.mockito.MockedStatic;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.meta.ModelManager;

import static org.mockito.Mockito.mockStatic;

/**
 * A mocked {@link ModelManager} that knows the fields of entity classes as their annotations declare them — every
 * {@code @Field} up the class chain, audit fields included, stored unless {@code dynamic} — so a test of the
 * conditions a caller adds to a list follows the entities as they change. Any other name does not exist.
 */
public final class StoredFieldsFixture {

    private StoredFieldsFixture() {
    }

    /** Opens the mock; close it after the test. */
    public static MockedStatic<ModelManager> mockModelManager(Class<?>... entities) {
        MockedStatic<ModelManager> manager = mockStatic(ModelManager.class);
        for (Class<?> entity : entities) {
            String model = entity.getSimpleName();
            for (Class<?> type = entity; type != null && type != Object.class; type = type.getSuperclass()) {
                for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                    Field declared = field.getAnnotation(Field.class);
                    if (declared == null) {
                        continue;
                    }
                    String name = field.getName();
                    manager.when(() -> ModelManager.existField(model, name)).thenReturn(true);
                    manager.when(() -> ModelManager.isStored(model, name)).thenReturn(!declared.dynamic());
                }
            }
        }
        return manager;
    }
}
