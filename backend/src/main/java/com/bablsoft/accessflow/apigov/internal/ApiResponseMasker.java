package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiMaskingMatcherType;
import com.bablsoft.accessflow.apigov.api.ResolvedApiMask;
import com.bablsoft.accessflow.core.api.ColumnMasker;
import com.bablsoft.accessflow.core.api.MaskingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.xml.sax.InputSource;

/**
 * Applies resolved connector masking policies (AF-518) to an API response before it is snapshotted,
 * reusing {@link ColumnMasker#apply} for the actual transform so behaviour matches the SQL path. A
 * mask targets a field four ways:
 * <ul>
 *   <li>{@code JSON_PATH} / {@code SCHEMA_FIELD} — a dot-path into the JSON body, descending through
 *       arrays; a path landing on a sub-tree masks every leaf beneath it.</li>
 *   <li>{@code XML_PATH} — an XPath into an XML/SOAP body, masking each matched element's text.</li>
 *   <li>{@code REGEX} — a regular expression over a JSON or text body; the first capturing group
 *       (or the whole match when there is none) is masked.</li>
 * </ul>
 * JSON-tree masks apply to JSON bodies, XML-path masks to XML bodies, and regex masks to whatever
 * remains; non-matching bodies are returned unchanged. The legacy dot-path overload keeps the old
 * per-permission {@code restricted_response_fields} (FULL mask) working.
 */
@Component
public class ApiResponseMasker {

    private static final Logger log = LoggerFactory.getLogger(ApiResponseMasker.class);

    private final ObjectMapper objectMapper;

    public ApiResponseMasker(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Legacy back-compat: FULL-mask each dot-path (per-permission {@code restricted_response_fields}). */
    public String mask(String body, List<String> restrictedPaths) {
        if (restrictedPaths == null || restrictedPaths.isEmpty()) {
            return body;
        }
        return mask(body, null, restrictedPaths.stream()
                .filter(p -> p != null && !p.isBlank())
                .map(ResolvedApiMask::legacyRestrictedField)
                .toList());
    }

    /**
     * Applies all resolved masks to {@code body}. {@code contentType} hints XML detection; the body's
     * own shape is the fallback. Returns {@code body} unchanged when there is nothing to mask.
     */
    public String mask(String body, String contentType, List<ResolvedApiMask> masks) {
        if (body == null || body.isBlank() || masks == null || masks.isEmpty()) {
            return body;
        }
        var jsonTreeMasks = new ArrayList<ResolvedApiMask>();
        var xmlMasks = new ArrayList<ResolvedApiMask>();
        var regexMasks = new ArrayList<ResolvedApiMask>();
        for (var mask : masks) {
            if (mask == null || mask.fieldRef() == null || mask.fieldRef().isBlank()) {
                continue;
            }
            switch (mask.matcherType()) {
                case JSON_PATH, SCHEMA_FIELD -> jsonTreeMasks.add(mask);
                case XML_PATH -> xmlMasks.add(mask);
                case REGEX -> regexMasks.add(mask);
            }
        }

        var result = body;
        var json = tryParseJson(result);
        if (json != null) {
            result = applyJsonMasks(json, jsonTreeMasks);
        } else if (looksLikeXml(contentType, result)) {
            result = applyXmlMasks(result, xmlMasks);
        }
        return applyRegexMasks(result, regexMasks);
    }

    private JsonNode tryParseJson(String body) {
        try {
            var node = objectMapper.readTree(body);
            return node != null && (node.isObject() || node.isArray()) ? node : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private String applyJsonMasks(JsonNode root, List<ResolvedApiMask> masks) {
        if (masks.isEmpty()) {
            return objectMapper.writeValueAsString(root);
        }
        for (var mask : masks) {
            applyPath(root, mask.fieldRef().split("\\."), 0, mask.strategy(), mask.params());
        }
        return objectMapper.writeValueAsString(root);
    }

    private void applyPath(JsonNode node, String[] segments, int index, MaskingStrategy strategy,
                           Map<String, String> params) {
        if (node == null || index >= segments.length) {
            return;
        }
        if (node instanceof ArrayNode array) {
            for (var element : array) {
                applyPath(element, segments, index, strategy, params);
            }
            return;
        }
        if (node instanceof ObjectNode object) {
            var key = segments[index];
            if (index == segments.length - 1) {
                var value = object.get(key);
                if (value != null && value.isValueNode() && !value.isNull()) {
                    object.put(key, ColumnMasker.apply(strategy, value.asString(), params));
                } else if (value != null) {
                    maskAllLeaves(value, strategy, params);
                }
            } else {
                applyPath(object.get(key), segments, index + 1, strategy, params);
            }
        }
    }

    private void maskAllLeaves(JsonNode node, MaskingStrategy strategy, Map<String, String> params) {
        if (node instanceof ObjectNode object) {
            for (var name : object.propertyStream().map(Map.Entry::getKey).toList()) {
                var child = object.get(name);
                if (child != null && child.isValueNode() && !child.isNull()) {
                    object.put(name, ColumnMasker.apply(strategy, child.asString(), params));
                } else {
                    maskAllLeaves(child, strategy, params);
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (var element : array) {
                maskAllLeaves(element, strategy, params);
            }
        }
    }

    private static boolean looksLikeXml(String contentType, String body) {
        if (contentType != null && contentType.toLowerCase(java.util.Locale.ROOT).contains("xml")) {
            return true;
        }
        return body.stripLeading().startsWith("<");
    }

    private String applyXmlMasks(String body, List<ResolvedApiMask> masks) {
        if (masks.isEmpty()) {
            return body;
        }
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setExpandEntityReferences(false);
            var doc = factory.newDocumentBuilder().parse(new InputSource(new StringReader(body)));
            var xpathFactory = XPathFactory.newInstance();
            try {
                xpathFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            } catch (RuntimeException ignored) {
                // best-effort secure processing
            }
            var applied = false;
            for (var mask : masks) {
                var xpath = xpathFactory.newXPath();
                var nodes = (org.w3c.dom.NodeList) xpath.evaluate(mask.fieldRef(), doc,
                        XPathConstants.NODESET);
                for (var i = 0; i < nodes.getLength(); i++) {
                    var target = nodes.item(i);
                    var current = textValue(target);
                    if (current != null && !current.isEmpty()) {
                        target.setTextContent(ColumnMasker.apply(mask.strategy(), current, mask.params()));
                        applied = true;
                    }
                }
            }
            return applied ? serialize(doc) : body;
        } catch (Exception ex) {
            log.debug("XML masking skipped (unparseable or invalid XPath): {}", ex.getMessage());
            return body;
        }
    }

    private static String textValue(Node node) {
        if (node.getNodeType() == Node.ATTRIBUTE_NODE) {
            return node.getNodeValue();
        }
        return node.getTextContent();
    }

    private String serialize(Document doc) throws Exception {
        var transformerFactory = TransformerFactory.newInstance();
        try {
            transformerFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        } catch (RuntimeException ignored) {
            // best-effort hardening
        }
        var transformer = transformerFactory.newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        var writer = new StringWriter();
        transformer.transform(new DOMSource(doc), new StreamResult(writer));
        return writer.toString();
    }

    private String applyRegexMasks(String body, List<ResolvedApiMask> masks) {
        if (masks.isEmpty() || body == null) {
            return body;
        }
        var result = body;
        for (var mask : masks) {
            result = applyRegex(result, mask);
        }
        return result;
    }

    private String applyRegex(String body, ResolvedApiMask mask) {
        Pattern pattern;
        try {
            pattern = Pattern.compile(mask.fieldRef());
        } catch (RuntimeException ex) {
            log.debug("Skipping invalid masking regex '{}': {}", mask.fieldRef(), ex.getMessage());
            return body;
        }
        var matcher = pattern.matcher(body);
        var out = new StringBuilder();
        var hasGroup = matcher.groupCount() >= 1;
        while (matcher.find()) {
            if (hasGroup && matcher.group(1) != null) {
                var prefix = body.substring(matcher.start(), matcher.start(1));
                var suffix = body.substring(matcher.end(1), matcher.end());
                var masked = ColumnMasker.apply(mask.strategy(), matcher.group(1), mask.params());
                matcher.appendReplacement(out, Matcher.quoteReplacement(prefix + masked + suffix));
            } else {
                var masked = ColumnMasker.apply(mask.strategy(), matcher.group(), mask.params());
                matcher.appendReplacement(out, Matcher.quoteReplacement(masked));
            }
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
