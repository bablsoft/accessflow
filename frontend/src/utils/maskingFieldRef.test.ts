import { describe, expect, it } from 'vitest';
import { isValidJavaRegex, isValidMaskingFieldRef, isValidXPath } from './maskingFieldRef';

describe('isValidXPath', () => {
  it.each(['//ssn', '/Envelope/Body/user/ssn', '//user/@card', '//a | //b', "//price[text()='$5']", 'child::ssn', "//a[@t='x:y']", '//user/descendant-or-self::node()'])(
    'accepts %s',
    (expression) => {
      expect(isValidXPath(expression)).toBe(true);
    },
  );

  it.each(['//user[', '//ssn[@', 'count(//ssn)', 'string(//ssn)', '//ns:ssn', '//a[@x:y]', 'ns:ssn', '//ssn[$x]', "//a[@t='$x']/b[$y]"])(
    'rejects %s',
    (expression) => {
      expect(isValidXPath(expression)).toBe(false);
    },
  );
});

describe('isValidJavaRegex', () => {
  it.each(['\\d{3}-\\d{2}-\\d{4}', '"ssn":"([^"]+)"', '(?i)secret', '(?i:ssn)\\d+', 'a*+b', '(?>ab)c', '\\Aabc\\z', '\\Q(\\E'])(
    'accepts %s',
    (pattern) => {
      expect(isValidJavaRegex(pattern)).toBe(true);
    },
  );

  it.each(['(unclosed', '[a-z', '*leading', '\\'])('rejects %s', (pattern) => {
    expect(isValidJavaRegex(pattern)).toBe(false);
  });
});

describe('isValidMaskingFieldRef', () => {
  it('checks XML_PATH and REGEX only', () => {
    expect(isValidMaskingFieldRef('XML_PATH', '//user[')).toBe(false);
    expect(isValidMaskingFieldRef('REGEX', '(unclosed')).toBe(false);
    expect(isValidMaskingFieldRef('JSON_PATH', 'user[')).toBe(true);
    expect(isValidMaskingFieldRef('SCHEMA_FIELD', '(')).toBe(true);
    expect(isValidMaskingFieldRef(undefined, '(')).toBe(true);
  });

  it('leaves blank values to the required rule', () => {
    expect(isValidMaskingFieldRef('XML_PATH', undefined)).toBe(true);
    expect(isValidMaskingFieldRef('REGEX', '   ')).toBe(true);
  });

  it('trims before validating', () => {
    expect(isValidMaskingFieldRef('XML_PATH', '  //ssn  ')).toBe(true);
  });
});
