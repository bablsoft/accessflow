import { describe, expect, it } from 'vitest';
import type { TFunction } from 'i18next';
import {
  REQUEST_GROUP_ITEM_STATUSES,
  REQUEST_GROUP_STATUSES,
  REQUEST_GROUP_TARGET_KINDS,
  requestGroupItemStatusLabel,
  requestGroupStatusLabel,
  targetKindLabel,
} from './enumLabels';
import { requestGroupItemStatusColor, requestGroupStatusColor } from './statusColors';

const t = ((key: string) => key) as unknown as TFunction;

describe('request group enum labels', () => {
  it('maps every status to its enum key', () => {
    for (const v of REQUEST_GROUP_STATUSES) {
      expect(requestGroupStatusLabel(t, v)).toBe(`enums.request_group_status.${v}`);
    }
  });

  it('maps every item status to its enum key', () => {
    for (const v of REQUEST_GROUP_ITEM_STATUSES) {
      expect(requestGroupItemStatusLabel(t, v)).toBe(`enums.request_group_item_status.${v}`);
    }
  });

  it('maps every target kind to its enum key', () => {
    for (const v of REQUEST_GROUP_TARGET_KINDS) {
      expect(targetKindLabel(t, v)).toBe(`enums.request_group_target_kind.${v}`);
    }
  });
});

describe('request group status colors', () => {
  it('returns a color triple for every group status', () => {
    for (const v of REQUEST_GROUP_STATUSES) {
      const c = requestGroupStatusColor(v);
      expect(c.fg).toBeTruthy();
      expect(c.bg).toBeTruthy();
      expect(c.border).toBeTruthy();
    }
  });

  it('returns a color triple for every item status', () => {
    for (const v of REQUEST_GROUP_ITEM_STATUSES) {
      const c = requestGroupItemStatusColor(v);
      expect(c.fg).toBeTruthy();
      expect(c.bg).toBeTruthy();
      expect(c.border).toBeTruthy();
    }
  });
});
