import { describe, expect, it } from 'vitest';
import { routeLabel, routeLabelKey } from '../routeLabel';

/** Stand-in for `t()`: returns the key so assertions read as the mapping under test. */
const echo = (key: string) => key;

describe('routeLabel', () => {
  it('maps a top-level route to the sidebar label key', () => {
    expect(routeLabelKey('/editor')).toBe('nav.editor');
    expect(routeLabelKey('/reviews')).toBe('nav.reviews');
  });

  it('maps a detail route to its list label, never to the id', () => {
    const uuid = '3f2504e0-4f89-11d3-9a0c-0305e82c3301';
    const label = routeLabel(`/queries/${uuid}`, echo);
    expect(label).toBe('nav.queries');
    expect(label).not.toContain(uuid);
    expect(label).not.toMatch(/[0-9a-f]{8}-[0-9a-f]{4}/i);
  });

  it('never leaks a path segment from a nested detail route', () => {
    const uuid = '3f2504e0-4f89-11d3-9a0c-0305e82c3301';
    const label = routeLabel(`/datasources/${uuid}/settings`, echo);
    expect(label).toBe('nav.datasources');
    expect(label).not.toContain('/');
  });

  it('prefers the longest matching prefix', () => {
    expect(routeLabelKey('/reviews/attestations')).toBe('nav.attestation_reviews');
    expect(routeLabelKey('/request-groups/reviews')).toBe('nav.requestGroupReviews');
    expect(routeLabelKey('/admin/lifecycle/policies')).toBe('nav.lifecycle');
  });

  it('does not match a route that merely starts with the same characters', () => {
    expect(routeLabelKey('/queries-archive')).toBeNull();
  });

  it('ignores a trailing slash', () => {
    expect(routeLabelKey('/editor/')).toBe('nav.editor');
  });

  it('returns nothing for an unmapped route', () => {
    expect(routeLabelKey('/somewhere-new')).toBeNull();
    expect(routeLabel('/somewhere-new', echo)).toBeUndefined();
  });

  it('leaves the root path unmapped rather than guessing', () => {
    expect(routeLabelKey('/')).toBeNull();
  });
});
