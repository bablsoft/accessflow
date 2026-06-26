package com.bablsoft.accessflow.apigov.internal.schema;

import com.bablsoft.accessflow.apigov.api.ApiSchemaParseException;
import com.bablsoft.accessflow.apigov.api.ApiSchemaType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraphQlSchemaParserTest {

    private final GraphQlSchemaParser parser = new GraphQlSchemaParser();

    private static final String SDL = """
            type Query {
              users(limit: Int): [User]
              user(id: ID!): User
            }
            type Mutation {
              createUser(name: String!): User
            }
            type User { id: ID! name: String }
            """;

    @Test
    void supportedTypeIsGraphQlSdl() {
        assertThat(parser.supportedType()).isEqualTo(ApiSchemaType.GRAPHQL_SDL);
    }

    @Test
    void parsesQueryAndMutationFields() {
        var ops = parser.parse(SDL);

        var query = ops.stream().filter(o -> o.operationId().equals("users")).findFirst().orElseThrow();
        assertThat(query.verb()).isEqualTo("query");
        assertThat(query.write()).isFalse();
        var mutation = ops.stream().filter(o -> o.operationId().equals("createUser")).findFirst().orElseThrow();
        assertThat(mutation.verb()).isEqualTo("mutation");
        assertThat(mutation.write()).isTrue();
        assertThat(ops).extracting("operationId").contains("users", "user", "createUser");
    }

    @Test
    void rejectsSdlWithoutRootTypes() {
        assertThatThrownBy(() -> parser.parse("type User { id: ID! }"))
                .isInstanceOf(ApiSchemaParseException.class);
    }

    @Test
    void rejectsBlank() {
        assertThatThrownBy(() -> parser.parse("  ")).isInstanceOf(ApiSchemaParseException.class);
    }
}
