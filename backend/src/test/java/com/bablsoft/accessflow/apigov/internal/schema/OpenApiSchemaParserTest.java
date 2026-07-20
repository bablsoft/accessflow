package com.bablsoft.accessflow.apigov.internal.schema;

import com.bablsoft.accessflow.apigov.api.ApiSchemaParseException;
import com.bablsoft.accessflow.apigov.api.ApiSchemaType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenApiSchemaParserTest {

    private final OpenApiSchemaParser parser = new OpenApiSchemaParser();

    private static final String SPEC = """
            openapi: 3.0.0
            info:
              title: Pet API
              version: 1.0.0
            paths:
              /pets:
                get:
                  operationId: listPets
                  summary: List pets
                  tags: [pets, public]
                  responses:
                    '200': { description: ok }
                post:
                  operationId: createPet
                  responses:
                    '201': { description: created }
              /pets/{id}:
                delete:
                  deprecated: true
                  responses:
                    '204': { description: gone }
            """;

    @Test
    void supportedTypeIsOpenApi() {
        assertThat(parser.supportedType()).isEqualTo(ApiSchemaType.OPENAPI);
    }

    @Test
    void parsesOperationsWithReadWriteClassification() {
        var ops = parser.parse(SPEC).operations();

        assertThat(ops).hasSize(3);
        var get = ops.stream().filter(o -> o.operationId().equals("listPets")).findFirst().orElseThrow();
        assertThat(get.verb()).isEqualTo("GET");
        assertThat(get.path()).isEqualTo("/pets");
        assertThat(get.write()).isFalse();
        var post = ops.stream().filter(o -> o.operationId().equals("createPet")).findFirst().orElseThrow();
        assertThat(post.write()).isTrue();
        // DELETE without operationId gets a synthesized id and is a write.
        var del = ops.stream().filter(o -> o.verb().equals("DELETE")).findFirst().orElseThrow();
        assertThat(del.write()).isTrue();
        assertThat(del.operationId()).contains("/pets/{id}");
    }

    @Test
    void capturesTagsAndDeprecatedForOpenApi() {
        var ops = parser.parse(SPEC).operations();

        var get = ops.stream().filter(o -> o.operationId().equals("listPets")).findFirst().orElseThrow();
        assertThat(get.tags()).containsExactlyInAnyOrder("pets", "public");
        var del = ops.stream().filter(o -> o.verb().equals("DELETE")).findFirst().orElseThrow();
        assertThat(del.deprecated()).isTrue();
        var post = ops.stream().filter(o -> o.operationId().equals("createPet")).findFirst().orElseThrow();
        assertThat(post.deprecated()).isNull();
    }

    @Test
    void rejectsInvalidDocument() {
        assertThatThrownBy(() -> parser.parse("not a spec at all"))
                .isInstanceOf(ApiSchemaParseException.class);
    }

    @Test
    void rejectsDocumentWithNoPaths() {
        assertThatThrownBy(() -> parser.parse("openapi: 3.0.0\ninfo:\n  title: x\n  version: 1.0.0\n"))
                .isInstanceOf(ApiSchemaParseException.class);
    }
}
