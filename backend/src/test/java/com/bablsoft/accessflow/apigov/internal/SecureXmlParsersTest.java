package com.bablsoft.accessflow.apigov.internal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecureXmlParsersTest {

    @Test
    void parsesWellFormedXml() throws Exception {
        var doc = SecureXmlParsers.parse("<r a=\"1\"><c>v</c></r>");

        assertThat(doc.getDocumentElement().getNodeName()).isEqualTo("r");
        assertThat(doc.getDocumentElement().getAttribute("a")).isEqualTo("1");
    }

    @Test
    void rejectsADoctypeDeclaration() {
        var xxe = """
                <?xml version="1.0"?>
                <!DOCTYPE r [<!ENTITY x SYSTEM "file:///etc/passwd">]>
                <r>&x;</r>""";

        assertThatThrownBy(() -> SecureXmlParsers.parse(xxe))
                .isInstanceOf(SecureXmlParsers.XmlParseException.class);
    }

    @Test
    void rejectsMalformedXml() {
        assertThatThrownBy(() -> SecureXmlParsers.parse("<r>"))
                .isInstanceOf(SecureXmlParsers.XmlParseException.class);
    }
}
