import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import '@/i18n';
import { AttestationUsageCell } from './AttestationUsageCell';
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

describe('AttestationUsageCell', () => {
  /**
   * The distinction this component exists to preserve: an unmeasured grant must not look like an
   * unused one, or a reviewer revokes access on the strength of missing data.
   */
  it('shows "no usage data" when the grant was never summarised', () => {
    render(<AttestationUsageCell item={item()} />);

    expect(screen.getByText('No usage data')).toBeInTheDocument();
    expect(screen.queryByText('Never used')).not.toBeInTheDocument();
  });

  it('shows "never used" when the grant was measured and never exercised', () => {
    render(
      <AttestationUsageCell
        item={item({
          usage_recommendation: 'NEVER_USED',
          usage_count: 0,
          usage_last_used_at: null,
          usage_granted_target_count: 4,
          usage_used_target_count: 0,
        })}
      />,
    );

    // The pill states the verdict; the detail line repeats it alongside the exercised scope.
    expect(screen.getByText('Never used')).toBeInTheDocument();
    expect(screen.getByText(/Never used · 0\/4 in scope/)).toBeInTheDocument();
    expect(screen.queryByText('No usage data')).not.toBeInTheDocument();
  });

  it('shows the last-used date and the exercised scope for a measured grant', () => {
    render(
      <AttestationUsageCell
        item={item({
          usage_recommendation: 'OVER_SCOPED',
          usage_count: 12,
          usage_last_used_at: '2026-05-30T16:41:00Z',
          usage_granted_target_count: 9,
          usage_used_target_count: 2,
        })}
      />,
    );

    expect(screen.getByText('Over-scoped')).toBeInTheDocument();
    expect(screen.getByText(/2\/9 in scope/)).toBeInTheDocument();
  });

  /** An unrestricted grant has no denominator, so the ratio must be omitted rather than faked. */
  it('omits the scope ratio for an unrestricted grant', () => {
    render(
      <AttestationUsageCell
        item={item({
          usage_recommendation: 'ACTIVE',
          usage_count: 30,
          usage_last_used_at: '2026-05-30T16:41:00Z',
          usage_granted_target_count: null,
          usage_used_target_count: 5,
        })}
      />,
    );

    expect(screen.getByText('Active')).toBeInTheDocument();
    expect(screen.queryByText(/in scope/)).not.toBeInTheDocument();
  });

  /**
   * INSUFFICIENT_DATA also has a null last-used timestamp. Branching on the timestamp alone would
   * label a grant that is simply too new to judge as "never used" — the same conflation this
   * component exists to prevent, one state over.
   */
  it('distinguishes a too-new grant from one measured and never used', () => {
    render(
      <AttestationUsageCell
        item={item({
          usage_recommendation: 'INSUFFICIENT_DATA',
          usage_count: 0,
          usage_last_used_at: null,
          usage_granted_target_count: 4,
          usage_used_target_count: 0,
        })}
      />,
    );

    expect(screen.getByText(/Not enough history yet/)).toBeInTheDocument();
    expect(screen.queryByText(/Never used/)).not.toBeInTheDocument();
  });
});
