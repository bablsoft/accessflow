import type { ApiMaskingMatcherType } from '@/types/api';

/**
 * Mirrors the backend's write-time field_ref syntax check for API connector masking policies
 * (#1110): an XML_PATH must be an XPath that selects nodes, a REGEX must compile. The server is
 * authoritative — where the browser cannot judge (no XPath engine, Java-only regex syntax) the
 * value is accepted and left to the server.
 */
export function isValidMaskingFieldRef(
  matcherType: ApiMaskingMatcherType | undefined,
  fieldRef: string | undefined,
): boolean {
  const value = fieldRef?.trim();
  if (!value) {
    return true;
  }
  switch (matcherType) {
    case 'XML_PATH':
      return isValidXPath(value);
    case 'REGEX':
      return isValidJavaRegex(value);
    default:
      return true;
  }
}

/** Drops the contents of XPath string literals so a lexical scan only sees the expression itself. */
function withoutLiterals(expression: string): string {
  return expression.replace(/'[^']*'|"[^"]*"/g, "''");
}

/**
 * The backend binds no XPath variables and parses bodies without namespace awareness (a prefixed
 * step never matches), so both are rejected. `child::x` axes are not prefixes.
 */
const VARIABLE_OR_PREFIX = /\$|(?:^|[^\w.:-])[A-Za-z_][\w.-]*:(?!:)/;

export function isValidXPath(expression: string): boolean {
  if (VARIABLE_OR_PREFIX.test(withoutLiterals(expression))) {
    return false;
  }
  if (typeof document === 'undefined' || typeof XPathResult === 'undefined') {
    return true;
  }
  const doc = document.implementation.createDocument(null, null, null);
  try {
    doc.evaluate(expression, doc, null, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null);
    return true;
  } catch {
    return false;
  }
}

/** Java-only constructs rewritten to a JS equivalent so the browser does not reject a valid pattern. */
const JAVA_ONLY_SYNTAX: ReadonlyArray<[RegExp, string]> = [
  [/\(\?[idmsuxU]*(?:-[idmsuxU]*)?\)/g, ''],
  [/\(\?[idmsuxU]*(?:-[idmsuxU]*)?:/g, '(?:'],
  [/\(\?>/g, '(?:'],
  [/([*+?}])\+/g, '$1'],
  [/\\[AGZz]/g, ''],
];

export function isValidJavaRegex(pattern: string): boolean {
  if (pattern.includes('\\Q')) {
    return true;
  }
  let normalized = pattern;
  for (const [javaSyntax, replacement] of JAVA_ONLY_SYNTAX) {
    normalized = normalized.replace(javaSyntax, replacement);
  }
  try {
    new RegExp(normalized);
    return true;
  } catch {
    return false;
  }
}
