package io.softa.framework.web.apidocs;

import java.lang.annotation.Annotation;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import org.junit.jupiter.api.Test;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.MaskingType;
import io.softa.framework.orm.enums.OnDelete;
import io.softa.framework.orm.enums.WidgetType;

import static org.junit.jupiter.api.Assertions.*;

class FieldAnnotationCustomizerTest {

    private final FieldAnnotationCustomizer customizer = new FieldAnnotationCustomizer();

    @Test
    void appliesLabelNameAndDescription() {
        Schema<?> result = customize(new StringSchema(), fieldAnnotation("Customer Name", "Full legal name", false, 0));

        assertEquals("Customer Name", result.getTitle());
        assertEquals("Full legal name", result.getDescription());
    }

    @Test
    void preservesUserProvidedTitleAndDescription() {
        Schema<?> seed = new StringSchema().title("Existing").description("Existing Desc");

        Schema<?> result = customize(seed, fieldAnnotation("Customer Name", "Full legal name", false, 0));

        assertEquals("Existing", result.getTitle());
        assertEquals("Existing Desc", result.getDescription());
    }

    @Test
    void setsReadOnlyWhenAnnotated() {
        Schema<?> result = customize(new StringSchema(), fieldAnnotation("", "", true, 0));

        assertTrue(result.getReadOnly());
    }

    @Test
    void setsMaxLengthOnlyForStringType() {
        Schema<?> stringSchema = customize(new StringSchema(), fieldAnnotation("", "", false, 100));
        Schema<?> intSchema = customize(new IntegerSchema(), fieldAnnotation("", "", false, 100));

        assertEquals(100, stringSchema.getMaxLength());
        assertNull(intSchema.getMaxLength());
    }

    @Test
    void leavesPropertyUnchangedWhenCtxAnnotationsNull() {
        Schema<?> seed = new StringSchema();
        AnnotatedType type = new AnnotatedType(String.class);

        Schema<?> result = customizer.customize(seed, type);

        assertNull(result.getTitle());
        assertNull(result.getDescription());
    }

    @Test
    void skipsNonFieldAnnotations() {
        Schema<?> seed = new StringSchema();
        AnnotatedType type = new AnnotatedType(String.class).ctxAnnotations(new Annotation[] { otherAnnotation() });

        Schema<?> result = customizer.customize(seed, type);

        assertNull(result.getTitle());
    }

    private Schema<?> customize(Schema<?> seed, Field field) {
        AnnotatedType type = new AnnotatedType(String.class).ctxAnnotations(new Annotation[] { field });
        return customizer.customize(seed, type);
    }

    private static Field fieldAnnotation(String label, String description, boolean readonly, int length) {
        return new Field() {
            @Override public Class<? extends Annotation> annotationType() { return Field.class; }
            @Override public String label() { return label; }
            @Override public String description() { return description; }
            @Override public FieldType[] fieldType() { return new FieldType[0]; }
            @Override public String renamedFrom() { return ""; }
            @Override public String columnName() { return ""; }
            @Override public int length() { return length; }
            @Override public int scale() { return 0; }
            @Override public boolean required() { return false; }
            @Override public boolean readonly() { return readonly; }
            @Override public boolean translatable() { return false; }
            @Override public boolean copyable() { return true; }
            @Override public boolean unsearchable() { return false; }
            @Override public boolean computed() { return false; }
            @Override public String expression() { return ""; }
            @Override public boolean dynamic() { return false; }
            @Override public boolean encrypted() { return false; }
            @Override public boolean autoSequence() { return false; }
            @Override public MaskingType[] maskingType() { return new MaskingType[0]; }
            @Override public String defaultValue() { return ""; }
            @Override public Class<?> relatedModel() { return Void.class; }
            @Override public String relatedModelName() { return ""; }
            @Override public String relatedField() { return ""; }
            @Override public OnDelete[] onDelete() { return new OnDelete[0]; }
            @Override public Class<?> joinModel() { return Void.class; }
            @Override public String joinModelName() { return ""; }
            @Override public String joinLeft() { return ""; }
            @Override public String joinRight() { return ""; }
            @Override public String cascadedField() { return ""; }
            @Override public String filters() { return ""; }
            @Override public WidgetType[] widgetType() { return new WidgetType[0]; }
        };
    }

    private static Annotation otherAnnotation() {
        return new Deprecated() {
            @Override public Class<? extends Annotation> annotationType() { return Deprecated.class; }
            @Override public String since() { return ""; }
            @Override public boolean forRemoval() { return false; }
        };
    }
}
