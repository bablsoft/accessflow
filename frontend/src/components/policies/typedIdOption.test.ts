import { describe, expect, it } from 'vitest';
import { withTypedId } from './typedIdOption';

const ID = '3f2b8c1e-9a4d-4e6f-8b1a-2c3d4e5f6a7b';
const label = (id: string) => `Use ${id}`;

describe('withTypedId', () => {
  it('appends a typed UUID the list does not contain', () => {
    expect(withTypedId([{ value: 'a', label: 'A' }], `  ${ID} `, label)).toEqual([
      { value: 'a', label: 'A' },
      { value: ID, label: `Use ${ID}` },
    ]);
  });

  it('leaves the list alone for partial ids, plain text and ids already listed', () => {
    const options = [{ value: ID, label: 'Orders' }];
    expect(withTypedId(options, ID.slice(0, 10), label)).toEqual(options);
    expect(withTypedId(options, 'orders', label)).toEqual(options);
    expect(withTypedId(options, ID.toUpperCase(), label)).toEqual(options);
  });
});
