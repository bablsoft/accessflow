package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ResolvedApiMask;
import com.bablsoft.accessflow.core.api.ColumnMasker;
import com.bablsoft.accessflow.core.api.MaskingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.DOMException;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import javax.xml.xpath.XPathFactoryConfigurationException;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

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
 *
 * <p>Masking fails closed: when a mask that applies cannot be evaluated (invalid XPath or regex, an
 * XPath that does not select nodes) or the body cannot be parsed in the format its masks target
 * (including a JSON body cut at the response cap), the whole body is replaced with
 * {@link #REDACTED_BODY} rather than returned raw.
 */
@Component
public class ApiResponseMasker {

    private static final Logger log = LoggerFactory.getLogger(ApiResponseMasker.class);

    static final String REDACTED_BODY = ColumnMasker.FULL_MASK;

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

        try {
            var result = body;
            var json = parseJson(contentType, result, jsonTreeMasks);
            if (json != null) {
                result = applyJsonMasks(json, jsonTreeMasks);
            } else if (looksLikeXml(contentType, result)) {
                result = applyXmlMasks(result, xmlMasks);
            }
            return applyRegexMasks(result, regexMasks);
        } catch (MaskingFailedException ex) {
            log.warn("API response fully redacted: masking policy {} could not be applied ({})",
                    ex.policyId, ex.getMessage());
            return REDACTED_BODY;
        }
    }

    /**
     * Returns the parsed JSON object/array, or {@code null} when the body is not JSON. A body that
     * looks like JSON but does not parse fails closed when JSON-tree masks target it.
     */
    private JsonNode parseJson(String contentType, String body, List<ResolvedApiMask> jsonTreeMasks) {
        try {
            var node = objectMapper.readTree(body);
            return node != null && (node.isObject() || node.isArray()) ? node : null;
        } catch (JacksonException ex) {
            if (!jsonTreeMasks.isEmpty() && looksLikeJson(contentType, body)) {
                throw new MaskingFailedException(jsonTreeMasks.getFirst(), "JSON body could not be parsed");
            }
            return null;
        }
    }

    private static boolean looksLikeJson(String contentType, String body) {
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("json")) {
            return true;
        }
        var trimmed = body.stripLeading();
        return trimmed.startsWith("{") || trimmed.startsWith("[");
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
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("xml")) {
            return true;
        }
        return body.stripLeading().startsWith("<");
    }

    private String applyXmlMasks(String body, List<ResolvedApiMask> masks) {
        if (masks.isEmpty()) {
            return body;
        }
        var doc = parseXml(body, masks.getFirst());
        var xpathFactory = XPathFactory.newInstance();
        try {
            xpathFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        } catch (XPathFactoryConfigurationException ignored) {
            // best-effort secure processing
        }
        var applied = false;
        for (var mask : masks) {
            applied |= applyXPath(doc, xpathFactory, mask);
        }
        return applied ? serialize(doc, masks.getFirst()) : body;
    }

    private static Document parseXml(String body, ResolvedApiMask mask) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(body)));
        } catch (ParserConfigurationException | SAXException | IOException ex) {
            throw new MaskingFailedException(mask, "XML body could not be parsed");
        }
    }

    private static boolean applyXPath(Document doc, XPathFactory xpathFactory, ResolvedApiMask mask) {
        NodeList nodes;
        try {
            nodes = (NodeList) xpathFactory.newXPath().evaluate(mask.fieldRef(), doc, XPathConstants.NODESET);
        } catch (XPathExpressionException ex) {
            throw new MaskingFailedException(mask, "invalid XPath '" + mask.fieldRef() + "'");
        }
        var applied = false;
        for (var i = 0; i < nodes.getLength(); i++) {
            var target = nodes.item(i);
            var current = textValue(target);
            if (current != null && !current.isEmpty()) {
                try {
                    target.setTextContent(ColumnMasker.apply(mask.strategy(), current, mask.params()));
                } catch (DOMException ex) {
                    throw new MaskingFailedException(mask, "XPath '" + mask.fieldRef()
                            + "' selected a node that cannot be masked");
                }
                applied = true;
            }
        }
        return applied;
    }

    private static String textValue(Node node) {
        if (node.getNodeType() == Node.ATTRIBUTE_NODE) {
            return node.getNodeValue();
        }
        return node.getTextContent();
    }

    private static String serialize(Document doc, ResolvedApiMask mask) {
        try {
            var transformerFactory = TransformerFactory.newInstance();
            try {
                transformerFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            } catch (TransformerConfigurationException | IllegalArgumentException ignored) {
                // best-effort hardening
            }
            var transformer = transformerFactory.newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            var writer = new StringWriter();
            transformer.transform(new DOMSource(doc), new StreamResult(writer));
            return writer.toString();
        } catch (TransformerException ex) {
            throw new MaskingFailedException(mask, "masked XML could not be serialized");
        }
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
        } catch (PatternSyntaxException ex) {
            throw new MaskingFailedException(mask, "invalid regex '" + mask.fieldRef() + "'");
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

    /** A mask that applies could not be evaluated; the caller redacts the whole body. */
    private static final class MaskingFailedException extends RuntimeException {

        private final UUID policyId;

        MaskingFailedException(ResolvedApiMask mask, String reason) {
            super(reason);
            this.policyId = mask.policyId();
        }
    }
}
