package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiMaskingMatcherType;
import org.w3c.dom.Document;

import javax.xml.XMLConstants;
import javax.xml.namespace.NamespaceContext;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import javax.xml.xpath.XPathFactoryConfigurationException;
import java.util.Collections;
import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Write-time syntax check for a masking {@code field_ref} (#1110). {@link ApiResponseMasker} fails
 * closed on a matcher it cannot evaluate, redacting the whole response, so a typo is rejected here
 * instead. The XPath is evaluated to a node-set against an empty document — the same evaluation the
 * masker runs — which also rejects expressions that compile but select no nodes ({@code count(//x)}).
 * The masker binds no variables and parses bodies without namespace awareness, so a variable
 * reference (throws at evaluation) or a namespace prefix (never matches) is rejected too.
 */
final class ApiMaskingFieldRefValidator {

    static final String INVALID_XPATH_KEY = "error.api_masking_policy_invalid_xpath";
    static final String INVALID_REGEX_KEY = "error.api_masking_policy_invalid_regex";

    private ApiMaskingFieldRefValidator() {
    }

    /** Returns the i18n key of the violation, if any; the key takes the field ref as argument {0}. */
    static Optional<String> validate(ApiMaskingMatcherType matcherType, String fieldRef) {
        return switch (matcherType) {
            case XML_PATH -> isValidXPath(fieldRef) ? Optional.empty() : Optional.of(INVALID_XPATH_KEY);
            case REGEX -> isValidRegex(fieldRef) ? Optional.empty() : Optional.of(INVALID_REGEX_KEY);
            case SCHEMA_FIELD, JSON_PATH -> Optional.empty();
        };
    }

    private static boolean isValidXPath(String expression) {
        if (referencesVariable(expression)) {
            return false;
        }
        var xpathFactory = XPathFactory.newInstance();
        try {
            xpathFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        } catch (XPathFactoryConfigurationException ignored) {
            // best-effort secure processing, as in ApiResponseMasker
        }
        var prefixUsed = new AtomicBoolean();
        var xpath = xpathFactory.newXPath();
        xpath.setNamespaceContext(new PrefixRecordingNamespaceContext(prefixUsed));
        try {
            xpath.evaluate(expression, emptyDocument(), XPathConstants.NODESET);
            return !prefixUsed.get();
        } catch (XPathExpressionException ex) {
            return false;
        }
    }

    /** XPath 1.0 has no {@code $} outside a variable reference or a string literal. */
    private static boolean referencesVariable(String expression) {
        var quote = (char) 0;
        for (var i = 0; i < expression.length(); i++) {
            var c = expression.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '$') {
                return true;
            }
        }
        return false;
    }

    private static Document emptyDocument() {
        try {
            return DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
        } catch (ParserConfigurationException ex) {
            throw new IllegalStateException("No default XML DocumentBuilder available", ex);
        }
    }

    private record PrefixRecordingNamespaceContext(AtomicBoolean prefixUsed) implements NamespaceContext {

        @Override
        public String getNamespaceURI(String prefix) {
            prefixUsed.set(true);
            return XMLConstants.NULL_NS_URI;
        }

        @Override
        public String getPrefix(String namespaceUri) {
            return null;
        }

        @Override
        public Iterator<String> getPrefixes(String namespaceUri) {
            return Collections.emptyIterator();
        }
    }

    private static boolean isValidRegex(String regex) {
        try {
            Pattern.compile(regex);
            return true;
        } catch (PatternSyntaxException ex) {
            return false;
        }
    }
}
