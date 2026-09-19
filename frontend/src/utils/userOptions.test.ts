import { describe, expect, it } from 'vitest';
import { userOptionLabel, userSelectOptions } from './userOptions';

describe('userSelectOptions (#875)', () => {
  it('labels with "Name (email)" or the bare email and keeps the label a string', () => {
    expect(userOptionLabel({ email: 'a@example.com', display_name: 'Alice' })).toBe(
      'Alice (a@example.com)',
    );
    expect(userOptionLabel({ email: 'a@example.com', display_name: '' })).toBe('a@example.com');
  });

  it('carries the principal type and defaults an absent one to a person', () => {
    const options = userSelectOptions([
      { id: 'u-1', email: 'bot@example.com', display_name: 'Bot', principal_type: 'SERVICE_ACCOUNT' },
      { id: 'u-2', email: 'alice@example.com', display_name: 'Alice' },
    ]);
    expect(options).toEqual([
      { value: 'u-1', label: 'Bot (bot@example.com)', principal_type: 'SERVICE_ACCOUNT' },
      { value: 'u-2', label: 'Alice (alice@example.com)', principal_type: 'HUMAN' },
    ]);
  });
});
