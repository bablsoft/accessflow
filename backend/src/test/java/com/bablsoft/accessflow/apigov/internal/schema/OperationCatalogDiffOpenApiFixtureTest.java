package com.bablsoft.accessflow.apigov.internal.schema;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static com.bablsoft.accessflow.apigov.api.ApiSchemaDriftKind.CLASSIFICATION_FLIPPED;
import static com.bablsoft.accessflow.apigov.api.ApiSchemaDriftKind.OPERATION_ADDED;
import static com.bablsoft.accessflow.apigov.api.ApiSchemaDriftKind.OPERATION_REMOVED;
import static com.bablsoft.accessflow.apigov.api.ApiSchemaDriftKind.SHAPE_CHANGED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** Runs the diff over real {@link OpenApiSchemaParser} output rather than hand-built records. */
class OperationCatalogDiffOpenApiFixtureTest {

    // Same document as OpenApiSchemaParserTest.SPEC.
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

    // createPet removed; listPets moved from GET to POST; the id-less DELETE became a PATCH.
    private static final String CHANGED = """
            openapi: 3.0.0
            info:
              title: Pet API
              version: 1.1.0
            paths:
              /pets:
                post:
                  operationId: listPets
                  summary: List pets
                  tags: [pets, public]
                  responses:
                    '200': { description: ok }
              /pets/{id}:
                patch:
                  deprecated: true
                  responses:
                    '204': { description: gone }
            """;

    private final OpenApiSchemaParser parser = new OpenApiSchemaParser();
    private final OperationCatalogDiff diff = new OperationCatalogDiff(new ObjectMapper());

    @Test
    void reparsingTheSameDocumentYieldsNoFindingsAndTheSameHash() {
        var first = parser.parse(SPEC).operations();
        var second = parser.parse(SPEC).operations();

        assertThat(diff.diff(first, second)).isEmpty();
        assertThat(diff.contentHash(second)).isEqualTo(diff.contentHash(first));
    }

    @Test
    void removedOperationAndVerbChangesProduceTheExactFindingList() {
        var current = parser.parse(SPEC).operations();
        var candidate = parser.parse(CHANGED).operations();

        var findings = diff.diff(current, candidate);

        // An operation without an operationId is keyed "VERB path", so its verb change reads as
        // removed + added; one with a declared id reports the shape change and the write flip.
        assertThat(findings)
                .extracting(OperationDiffFinding::kind, OperationDiffFinding::operationId)
                .containsExactly(
                        tuple(OPERATION_REMOVED, "DELETE /pets/{id}"),
                        tuple(OPERATION_ADDED, "PATCH /pets/{id}"),
                        tuple(OPERATION_REMOVED, "createPet"),
                        tuple(SHAPE_CHANGED, "listPets"),
                        tuple(CLASSIFICATION_FLIPPED, "listPets"));
        assertThat(diff.contentHash(candidate)).isNotEqualTo(diff.contentHash(current));
    }
}
