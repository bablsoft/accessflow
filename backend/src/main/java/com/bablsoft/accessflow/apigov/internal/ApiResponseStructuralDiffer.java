package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiResponseDiffEntry;
import com.bablsoft.accessflow.apigov.api.ApiResponseDiffFormat;
import com.bablsoft.accessflow.apigov.api.ApiResponseDiffKind;
import com.bablsoft.accessflow.apigov.api.ApiResponseDiffUnsupportedException;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Pure structural diff of two response bodies (#887). JSON paths follow the masking policies'
 * dot-path convention with {@code [i]} array indexes (arrays compare by index); XML paths use one
 * {@code name[i]} segment per element (the index counts same-named siblings), {@code @attr} for
 * attributes and {@code #text} for an element's own text. An added or removed subtree is reported
 * once, at its root. Output stops at {@code maxEntries} with {@code truncated} set.
 */
final class ApiResponseStructuralDiffer {

    static final int MAX_VALUE_CHARS = 1024;

    private static final String ROOT_PATH = "$";

    private final ObjectMapper objectMapper;

    ApiResponseStructuralDiffer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    record Result(List<ApiResponseDiffEntry> entries, boolean truncated) {
    }

    /**
     * @throws ApiResponseDiffUnsupportedException when either body does not parse in {@code format};
     *         it carries the content type of the side that failed.
     */
    Result diff(String before, String beforeContentType, String after, String afterContentType,
                ApiResponseDiffFormat format, int maxEntries) {
        var collector = new Collector(maxEntries);
        return switch (format) {
            case JSON -> {
                compareJson("", parseJson(before, beforeContentType), parseJson(after, afterContentType),
                        collector);
                yield collector.result();
            }
            case XML -> {
                compareXml(XmlSide.of(parseXml(before, beforeContentType)),
                        XmlSide.of(parseXml(after, afterContentType)), collector);
                yield collector.result();
            }
        };
    }

    private JsonNode parseJson(String body, String contentType) {
        try {
            var node = body == null ? null : objectMapper.readTree(body);
            if (node == null || node.isMissingNode()) {
                throw new ApiResponseDiffUnsupportedException(contentType);
            }
            return node;
        } catch (JacksonException ex) {
            throw new ApiResponseDiffUnsupportedException(contentType);
        }
    }

    private static Element parseXml(String body, String contentType) {
        if (body == null) {
            throw new ApiResponseDiffUnsupportedException(contentType);
        }
        try {
            return SecureXmlParsers.parse(body).getDocumentElement();
        } catch (SecureXmlParsers.XmlParseException ex) {
            throw new ApiResponseDiffUnsupportedException(contentType);
        }
    }

    private static void compareJson(String path, JsonNode before, JsonNode after, Collector collector) {
        if (collector.stopped) {
            return;
        }
        if (before == null && after == null) {
            return;
        }
        if (before == null) {
            collector.add(path, ApiResponseDiffKind.ADDED, null, after.toString());
        } else if (after == null) {
            collector.add(path, ApiResponseDiffKind.REMOVED, before.toString(), null);
        } else if (before.isObject() && after.isObject()) {
            var names = new LinkedHashSet<String>();
            before.propertyNames().forEach(names::add);
            after.propertyNames().forEach(names::add);
            for (var name : names) {
                compareJson(path.isEmpty() ? name : path + "." + name, before.get(name), after.get(name),
                        collector);
            }
        } else if (before.isArray() && after.isArray()) {
            var size = Math.max(before.size(), after.size());
            for (var i = 0; i < size; i++) {
                compareJson(path + "[" + i + "]", before.get(i), after.get(i), collector);
            }
        } else if (!before.equals(after)) {
            collector.add(path, ApiResponseDiffKind.CHANGED, before.toString(), after.toString());
        }
    }

    private static void compareXml(XmlSide before, XmlSide after, Collector collector) {
        for (var entry : before.elements.entrySet()) {
            if (collector.stopped) {
                return;
            }
            var path = entry.getKey();
            if (!after.elements.containsKey(path)) {
                var parent = before.parents.get(path);
                if (parent == null || after.elements.containsKey(parent)) {
                    collector.add(path, ApiResponseDiffKind.REMOVED, entry.getValue().getTextContent().strip(),
                            null);
                }
                continue;
            }
            var beforeLeaves = before.leaves.get(path);
            var afterLeaves = after.leaves.get(path);
            for (var leaf : beforeLeaves.entrySet()) {
                var afterValue = afterLeaves.get(leaf.getKey());
                if (afterValue == null) {
                    collector.add(leaf.getKey(), ApiResponseDiffKind.REMOVED, leaf.getValue(), null);
                } else if (!afterValue.equals(leaf.getValue())) {
                    collector.add(leaf.getKey(), ApiResponseDiffKind.CHANGED, leaf.getValue(), afterValue);
                }
            }
            for (var leaf : afterLeaves.entrySet()) {
                if (!beforeLeaves.containsKey(leaf.getKey())) {
                    collector.add(leaf.getKey(), ApiResponseDiffKind.ADDED, null, leaf.getValue());
                }
            }
        }
        for (var entry : after.elements.entrySet()) {
            if (collector.stopped) {
                return;
            }
            var path = entry.getKey();
            var parent = after.parents.get(path);
            if (!before.elements.containsKey(path) && (parent == null || before.elements.containsKey(parent))) {
                collector.add(path, ApiResponseDiffKind.ADDED, null, entry.getValue().getTextContent().strip());
            }
        }
    }

    /** One parsed XML body flattened to element paths, their parents and their own leaves. */
    private static final class XmlSide {

        private final Map<String, Element> elements = new LinkedHashMap<>();
        private final Map<String, String> parents = new HashMap<>();
        private final Map<String, Map<String, String>> leaves = new HashMap<>();

        static XmlSide of(Element root) {
            var side = new XmlSide();
            side.flatten(root, root.getNodeName() + "[0]", null);
            return side;
        }

        private void flatten(Element element, String path, String parent) {
            elements.put(path, element);
            if (parent != null) {
                parents.put(path, parent);
            }
            var own = new LinkedHashMap<String, String>();
            leaves.put(path, own);
            var attributes = element.getAttributes();
            for (var i = 0; i < attributes.getLength(); i++) {
                var attribute = attributes.item(i);
                own.put(path + ".@" + attribute.getNodeName(), attribute.getNodeValue());
            }
            var text = new StringBuilder();
            var siblingCounts = new HashMap<String, Integer>();
            var children = element.getChildNodes();
            for (var i = 0; i < children.getLength(); i++) {
                var child = children.item(i);
                switch (child.getNodeType()) {
                    case Node.ELEMENT_NODE -> {
                        var name = child.getNodeName();
                        int index = siblingCounts.merge(name, 1, Integer::sum) - 1;
                        flatten((Element) child, path + "." + name + "[" + index + "]", path);
                    }
                    case Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> text.append(child.getNodeValue());
                    default -> {
                        // comments and processing instructions are not part of the payload
                    }
                }
            }
            var ownText = text.toString().strip();
            if (!ownText.isEmpty()) {
                own.put(path + ".#text", ownText);
            }
        }
    }

    private static final class Collector {

        private final int maxEntries;
        private final List<ApiResponseDiffEntry> entries = new ArrayList<>();
        private boolean truncated;
        private boolean stopped;

        Collector(int maxEntries) {
            this.maxEntries = maxEntries;
        }

        void add(String path, ApiResponseDiffKind kind, String before, String after) {
            if (stopped) {
                return;
            }
            if (entries.size() >= maxEntries) {
                truncated = true;
                stopped = true;
                return;
            }
            entries.add(new ApiResponseDiffEntry(path.isEmpty() ? ROOT_PATH : path, kind, clip(before),
                    clip(after)));
        }

        Result result() {
            return new Result(List.copyOf(entries), truncated);
        }

        private static String clip(String value) {
            return value != null && value.length() > MAX_VALUE_CHARS ? value.substring(0, MAX_VALUE_CHARS)
                    : value;
        }
    }
}
