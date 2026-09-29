import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import i18n from '@/i18n';
import { AttestationRowLimitNote } from './AttestationRowLimitNote';
import { rowLimitSourceLabel } from './rowLimitSource';
import type { AttestationItem } from '@/types/api';

function item(overrides: Partial<AttestationItem> = {}): AttestationItem {
  return {
    id: 'item-1',
    campaign_id: 'camp-1',
    permission_id: 'perm-1',
    datasource_id: 'ds-1',
    datasource_name: 'Prod Postgres',
    subject_user_id: 'u-1',
    subject_user_email: 'analyst@example.com',
    subject_user_display_name: 'Analyst One',
    can_read: true,
    can_write: false,
    can_ddl: false,
    can_break_glass: false,
    permission_expires_at: null,
    permission_created_at: '2026-06-01T00:00:00Z',
    row_limit_override: null,
    effective_row_limit: null,
    row_limit_source: null,
    usage_last_used_at: null,
    usage_count: null,
    usage_granted_target_count: null,
    usage_used_target_count: null,
    usage_recommendation: null,
    decision: 'PENDING',
    close_reason: null,
    decided_by: null,
    decided_at: null,
    decision_comment: null,
    created_at: '2026-06-20T00:00:00Z',
    ...overrides,
  };
}

describe('AttestationRowLimitNote', () => {
  it('shows the configured and the applied limit when the datasource cap clamps the grant', () => {
    render(
      <AttestationRowLimitNote
        item={item({
          row_limit_override: 5000,
          effective_row_limit: 1000,
          row_limit_source: 'datasource_cap',
        })}
      />,
    );
    expect(
      screen.getByText('Configured 5000 · applies 1000 (datasource cap)'),
    ).toBeInTheDocument();
  });

  it('names the group whose lower override applies', () => {
    render(
      <AttestationRowLimitNote
        item={item({
          row_limit_override: 500,
          effective_row_limit: 100,
          row_limit_source: 'group:analysts',
        })}
      />,
    );
    expect(screen.getByText('Configured 500 · applies 100 (group analysts)')).toBeInTheDocument();
  });

  it('shows the applied limit alone when the grant sets none but a group does', () => {
    render(
      <AttestationRowLimitNote
        item={item({ effective_row_limit: 100, row_limit_source: 'group:analysts' })}
      />,
    );
    expect(screen.getByText('Applies 100 (group analysts)')).toBeInTheDocument();
  });

  it('renders nothing when the configured limit is the one that applies', () => {
    const { container } = render(
      <AttestationRowLimitNote
        item={item({ row_limit_override: 500, effective_row_limit: 500, row_limit_source: 'grant' })}
      />,
    );
    expect(container).toBeEmptyDOMElement();
  });

  it('renders nothing for a grant without an override capped only by the datasource', () => {
    const { container } = render(
      <AttestationRowLimitNote
        item={item({ effective_row_limit: 1000, row_limit_source: 'datasource_cap' })}
      />,
    );
    expect(container).toBeEmptyDOMElement();
  });

  it('renders nothing for an item snapshotted before row-limit evidence existed', () => {
    const { container } = render(
      <AttestationRowLimitNote item={item({ row_limit_override: 5000 })} />,
    );
    expect(container).toBeEmptyDOMElement();
  });

  it('renders nothing for an unknown source', () => {
    const { container } = render(
      <AttestationRowLimitNote
        item={item({ row_limit_override: 5000, effective_row_limit: 10, row_limit_source: 'x' })}
      />,
    );
    expect(container).toBeEmptyDOMElement();
  });
});

describe('rowLimitSourceLabel', () => {
  const t = i18n.t.bind(i18n);

  it('labels every known source', () => {
    expect(rowLimitSourceLabel(t, 'grant')).toBe('this grant');
    expect(rowLimitSourceLabel(t, 'group:ops:east')).toBe('group ops:east');
    expect(rowLimitSourceLabel(t, 'datasource_cap')).toBe('datasource cap');
    expect(rowLimitSourceLabel(t, 'global_ceiling')).toBe('deployment ceiling');
  });

  it('returns null for an absent or unknown source', () => {
    expect(rowLimitSourceLabel(t, null)).toBeNull();
    expect(rowLimitSourceLabel(t, undefined)).toBeNull();
    expect(rowLimitSourceLabel(t, 'row_limit_policy')).toBeNull();
  });
});
