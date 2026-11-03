package com.bablsoft.accessflow.apigov.internal;

import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;

/**
 * The one place an upstream XML response body is parsed: DOCTYPE declarations are refused and
 * external / entity expansion is disabled (XXE, billion laughs). Shared by masking and the response
 * diff so the protections cannot be forgotten on a second parser.
 */
final class SecureXmlParsers {

    private SecureXmlParsers() {
    }

    static Document parse(String body) throws XmlParseException {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(body)));
        } catch (ParserConfigurationException | SAXException | IOException ex) {
            throw new XmlParseException(ex);
        }
    }

    static final class XmlParseException extends Exception {

        XmlParseException(Throwable cause) {
            super(cause.getMessage(), cause);
        }
    }
}
