import { describe, expect, it } from 'vitest';
import { documentsToJson, rowsToDocuments } from './resultDocuments';

describe('rowsToDocuments', () => {
  it('zips columns and rows into documents', () => {
    const docs = rowsToDocuments(
      [{ name: '_id' }, { name: 'name' }],
      [
        [1, 'Ada'],
        [2, 'Bo'],
      ],
    );
    expect(docs).toEqual([
      { _id: 1, name: 'Ada' },
      { _id: 2, name: 'Bo' },
    ]);
  });

  it('represents absent/undefined cells as null', () => {
    const docs = rowsToDocuments([{ name: 'a' }, { name: 'b' }], [[1, undefined]]);
    expect(docs).toEqual([{ a: 1, b: null }]);
  });

  it('preserves nested objects and arrays', () => {
    const docs = rowsToDocuments(
      [{ name: 'profile' }, { name: 'tags' }],
      [[{ city: 'NYC' }, ['x', 'y']]],
    );
    expect(docs[0]).toEqual({ profile: { city: 'NYC' }, tags: ['x', 'y'] });
  });

  it('pretty-prints documents as JSON', () => {
    const json = documentsToJson([{ name: 'a' }], [[1]]);
    expect(json).toBe('[\n  {\n    "a": 1\n  }\n]');
  });

  it('returns an empty array for no rows', () => {
    expect(rowsToDocuments([{ name: 'a' }], [])).toEqual([]);
  });
});
