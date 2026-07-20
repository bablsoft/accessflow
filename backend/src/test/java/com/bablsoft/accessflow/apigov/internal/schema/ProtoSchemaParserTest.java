package com.bablsoft.accessflow.apigov.internal.schema;

import com.bablsoft.accessflow.apigov.api.ApiSchemaParseException;
import com.bablsoft.accessflow.apigov.api.ApiSchemaType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProtoSchemaParserTest {

    private final ProtoSchemaParser parser = new ProtoSchemaParser();

    private static final String PROTO = """
            syntax = "proto3";
            package demo;
            service UserService {
              rpc GetUser (GetUserRequest) returns (User);
              rpc CreateUser (CreateUserRequest) returns (User);
              rpc ListUsers (ListUsersRequest) returns (stream User);
            }
            """;

    @Test
    void supportedTypeIsProto() {
        assertThat(parser.supportedType()).isEqualTo(ApiSchemaType.GRPC_PROTO);
    }

    @Test
    void parsesRpcsWithServicePrefixAndClassification() {
        var ops = parser.parse(PROTO).operations();

        assertThat(ops).hasSize(3);
        var get = ops.stream().filter(o -> o.operationId().equals("UserService.GetUser")).findFirst().orElseThrow();
        assertThat(get.write()).isFalse();
        var create = ops.stream().filter(o -> o.operationId().equals("UserService.CreateUser")).findFirst().orElseThrow();
        assertThat(create.write()).isTrue();
        var list = ops.stream().filter(o -> o.operationId().equals("UserService.ListUsers")).findFirst().orElseThrow();
        assertThat(list.write()).isFalse();
    }

    @Test
    void rejectsProtoWithoutServices() {
        assertThatThrownBy(() -> parser.parse("message Foo { string bar = 1; }"))
                .isInstanceOf(ApiSchemaParseException.class);
    }
}
