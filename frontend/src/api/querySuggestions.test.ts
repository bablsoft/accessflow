import { beforeEach, describe, expect, it, vi } from 'vitest';

const { get } = vi.hoisted(() => ({ get: vi.fn() }));

vi.mock('./client', () => ({ apiClient: { get } }));

const { fetchQuerySuggestions, querySuggestionKeys } = await import('./querySuggestions');

const DATASOURCE = 'ds-1';

describe('querySuggestions api', () => {
  beforeEach(() => {
    get.mockReset();
    get.mockResolvedValue({ data: { suggestions: [] } });
  });

  it('exposes stable query keys', () => {
    expect(querySuggestionKeys.all).toEqual(['query-suggestions']);
    expect(querySuggestionKeys.list(DATASOURCE)).toEqual([
      'query-suggestions',
      'list',
      DATASOURCE,
      null,
    ]);
    expect(querySuggestionKeys.list(DATASOURCE, 5)).toEqual([
      'query-suggestions',
      'list',
      DATASOURCE,
      5,
    ]);
  });

  it('unwraps the suggestions envelope', async () => {
    const suggestion = { id: 's-1', sql: 'select 1 from orders' };
    get.mockResolvedValue({ data: { suggestions: [suggestion] } });

    await expect(fetchQuerySuggestions(DATASOURCE)).resolves.toEqual([suggestion]);
    expect(get.mock.calls[0]?.[0]).toBe('/api/v1/datasources/ds-1/query-suggestions');
    expect(get.mock.calls[0]?.[1]).toEqual({ params: {} });
  });

  it('sends limit only when one was given', async () => {
    await fetchQuerySuggestions(DATASOURCE, 5);

    expect(get.mock.calls[0]?.[1]).toEqual({ params: { limit: 5 } });
  });
});
