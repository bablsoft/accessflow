package com.bablsoft.accessflow.apigov.internal.schema;

import com.bablsoft.accessflow.apigov.api.ApiSchemaParseException;
import com.bablsoft.accessflow.apigov.api.ApiSchemaType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WsdlSchemaParserTest {

    private final WsdlSchemaParser parser = new WsdlSchemaParser();

    private static final String WSDL = """
            <?xml version="1.0"?>
            <definitions xmlns="http://schemas.xmlsoap.org/wsdl/" name="Stock">
              <portType name="StockPort">
                <operation name="GetPrice"/>
                <operation name="SetPrice"/>
              </portType>
            </definitions>
            """;

    @Test
    void supportedTypeIsWsdl() {
        assertThat(parser.supportedType()).isEqualTo(ApiSchemaType.WSDL);
    }

    @Test
    void parsesPortTypeOperationsWithClassification() {
        var ops = parser.parse(WSDL);

        assertThat(ops).hasSize(2);
        var get = ops.stream().filter(o -> o.operationId().equals("GetPrice")).findFirst().orElseThrow();
        assertThat(get.write()).isFalse();
        var set = ops.stream().filter(o -> o.operationId().equals("SetPrice")).findFirst().orElseThrow();
        assertThat(set.write()).isTrue();
    }

    @Test
    void rejectsWsdlWithoutOperations() {
        assertThatThrownBy(() -> parser.parse("<definitions xmlns=\"http://schemas.xmlsoap.org/wsdl/\"/>"))
                .isInstanceOf(ApiSchemaParseException.class);
    }

    @Test
    void rejectsMalformedXml() {
        assertThatThrownBy(() -> parser.parse("<definitions><portType>")).
                isInstanceOf(ApiSchemaParseException.class);
    }
}
