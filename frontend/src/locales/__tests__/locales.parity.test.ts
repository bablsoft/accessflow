import { describe, expect, it } from 'vitest';
import { SUPPORTED_LANGUAGES, type Language } from '../../i18n';
import en from '../en.json';
import de from '../de.json';
import es from '../es.json';
import fr from '../fr.json';
import hy from '../hy.json';
import ru from '../ru.json';
import zhCN from '../zh-CN.json';

const LOCALES: Record<Exclude<Language, 'en'>, unknown> = {
  de,
  es,
  fr,
  hy,
  ru,
  'zh-CN': zhCN,
};

type Tree = Record<string, unknown>;

function flatten(tree: unknown, prefix = ''): Set<string> {
  const result = new Set<string>();
  if (tree === null || typeof tree !== 'object') {
    return result;
  }
  for (const [key, value] of Object.entries(tree as Tree)) {
    const path = prefix ? `${prefix}.${key}` : key;
    if (value !== null && typeof value === 'object') {
      for (const sub of flatten(value, path)) {
        result.add(sub);
      }
    } else {
      result.add(path);
    }
  }
  return result;
}

function diff(a: Set<string>, b: Set<string>): string[] {
  const out: string[] = [];
  for (const key of a) {
    if (!b.has(key)) out.push(key);
  }
  out.sort();
  return out;
}

const enKeys = flatten(en);
const nonEnglish = SUPPORTED_LANGUAGES.filter((l) => l !== 'en') as Array<Exclude<Language, 'en'>>;

describe('locale JSON parity with en.json', () => {
  it.each(nonEnglish)('%s defines every key from en.json', (locale) => {
    const keys = flatten(LOCALES[locale]);
    const missing = diff(enKeys, keys);
    expect(
      missing,
      `${locale}.json is missing ${missing.length} translation keys. First 10: ${missing.slice(0, 10).join(', ')}`,
    ).toEqual([]);
  });

  it.each(nonEnglish)('%s contains no keys absent from en.json', (locale) => {
    const keys = flatten(LOCALES[locale]);
    const orphans = diff(keys, enKeys);
    expect(
      orphans,
      `${locale}.json declares ${orphans.length} keys not present in en.json. First 10: ${orphans.slice(0, 10).join(', ')}`,
    ).toEqual([]);
  });
});
