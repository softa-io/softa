package io.softa.framework.web.apidocs;

import java.util.Iterator;
import java.util.List;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;

import io.softa.framework.orm.annotation.Model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ModelAnnotationConverterTest {

    private final ModelAnnotationConverter converter = new ModelAnnotationConverter();

    @Test
    void appliesLabelNameAsTitleAndDescription() {
        Schema<?> result = resolve(WithModel.class, new ObjectSchema());

        assertEquals("Customer", result.getTitle());
        assertEquals("Customer entity", result.getDescription());
    }

    @Test
    void preservesUserProvidedTitleAndDescription() {
        Schema<?> seed = new ObjectSchema().title("Existing Title").description("Existing Desc");

        Schema<?> result = resolve(WithModel.class, seed);

        assertEquals("Existing Title", result.getTitle());
        assertEquals("Existing Desc", result.getDescription());
    }

    @Test
    void leavesNonModelClassUntouched() {
        Schema<?> result = resolve(PlainPojo.class, new ObjectSchema());

        assertNull(result.getTitle());
        assertNull(result.getDescription());
    }

    @Test
    void passesThroughRefSchemaWithoutModification() {
        Schema<?> ref = new Schema<>().$ref("#/components/schemas/WithModel");

        Schema<?> result = resolve(WithModel.class, ref);

        assertNull(result.getTitle());
        assertNull(result.getDescription());
        assertEquals("#/components/schemas/WithModel", result.get$ref());
    }

    @Test
    void returnsNullWhenChainReturnsNull() {
        Schema<?> result = resolve(WithModel.class, null);

        assertNull(result);
    }

    @Test
    void ignoresModelWithEmptyLabelAndDescription() {
        Schema<?> result = resolve(EmptyModel.class, new ObjectSchema());

        assertNull(result.getTitle());
        assertNull(result.getDescription());
    }

    private Schema<?> resolve(Class<?> clazz, Schema<?> chainResult) {
        ModelConverter stub = (type, ctx, chain) -> chainResult;
        Iterator<ModelConverter> chain = List.<ModelConverter>of(stub).iterator();
        return converter.resolve(new AnnotatedType(clazz), null, chain);
    }

    @Model(label = "Customer", description = "Customer entity")
    private static class WithModel {}

    @Model
    private static class EmptyModel {}

    private static class PlainPojo {}
}
