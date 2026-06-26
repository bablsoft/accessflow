package com.bablsoft.accessflow.apigov.internal.schema;

import com.bablsoft.accessflow.apigov.api.ApiOperation;
import com.bablsoft.accessflow.apigov.api.ApiSchemaParseException;
import com.bablsoft.accessflow.apigov.api.ApiSchemaType;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Lightweight WSDL parser using the JDK DOM (no wsdl4j dependency). Extracts {@code <operation>}
 * elements declared under {@code <portType>}; read/write is heuristic on the operation-name prefix.
 * XXE-hardened (external entities + DTDs disabled).
 */
@Component
public class WsdlSchemaParser implements ApiSchemaParser {

    private static final Set<String> READ_PREFIXES =
            Set.of("get", "list", "find", "read", "search", "query", "retrieve", "lookup");

    @Override
    public ApiSchemaType supportedType() {
        return ApiSchemaType.WSDL;
    }

    @Override
    public List<ApiOperation> parse(String content) {
        if (content == null || content.isBlank()) {
            throw new ApiSchemaParseException("Empty WSDL document");
        }
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setExpandEntityReferences(false);
            var doc = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
            var portTypes = doc.getElementsByTagNameNS("*", "portType");
            var operations = new ArrayList<ApiOperation>();
            var seen = new java.util.HashSet<String>();
            for (int i = 0; i < portTypes.getLength(); i++) {
                var ops = ((Element) portTypes.item(i)).getElementsByTagNameNS("*", "operation");
                for (int j = 0; j < ops.getLength(); j++) {
                    var name = ((Element) ops.item(j)).getAttribute("name");
                    if (name != null && !name.isBlank() && seen.add(name)) {
                        operations.add(new ApiOperation(name, name, name, null, isWrite(name), null, null));
                    }
                }
            }
            if (operations.isEmpty()) {
                throw new ApiSchemaParseException("WSDL defines no portType operations");
            }
            return operations;
        } catch (ApiSchemaParseException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ApiSchemaParseException("Invalid WSDL document: " + ex.getMessage());
        }
    }

    private static boolean isWrite(String operation) {
        var lower = operation.toLowerCase();
        return READ_PREFIXES.stream().noneMatch(lower::startsWith);
    }
}
