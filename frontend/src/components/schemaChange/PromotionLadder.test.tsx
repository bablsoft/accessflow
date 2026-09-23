import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { App as AntdApp } from 'antd';
import '@/i18n';
import type { SchemaChangeLadder, SchemaChangeLadderRung, SchemaChangePromotion } from '@/types/api';
import { PromotionLadder } from './PromotionLadder';

const promotion = (status: SchemaChangePromotion['status'], extra: Partial<SchemaChangePromotion> = {}) => ({
  id: `p-${status}`,
  change_set_id: 's-1',
  environment_id: 'e-dev',
  environment_name: 'dev',
  datasource_id: 'd-1',
  request_group_id: 'g-1',
  status,
  statements_checksum: 'c',
  promoted_by: 'u-1',
  submitted_at: '2026-09-23T10:00:00Z',
  ...extra,
});

const rung = (name: string, extra: Partial<SchemaChangeLadderRung>): SchemaChangeLadderRung => ({
  environment_id: `e-${name}`,
  environment_name: name,
  sort_order: 0,
  datasource_id: 'd-1',
  state: 'BLOCKED',
  ...extra,
});

function renderLadder(rungs: SchemaChangeLadderRung[], handlers = { onPromote: vi.fn(), onCancel: vi.fn() }) {
  const ladder: SchemaChangeLadder = { change_set_id: 's-1', pipeline_id: 'p-1', rungs };
  render(
    <MemoryRouter>
      <AntdApp>
        <PromotionLadder ladder={ladder} onPromote={handlers.onPromote} onCancel={handlers.onCancel} />
      </AntdApp>
    </MemoryRouter>,
  );
  return handlers;
}

describe('PromotionLadder', () => {
  it('renders the specific reason for every blocked rung', () => {
    renderLadder([
      rung('dev', { state: 'PROMOTABLE' }),
      rung('staging', { blocker: 'LOWER_ENVIRONMENT_NOT_APPLIED', blocking_environment_name: 'dev' }),
      rung('prod', { blocker: 'FREEZE_ACTIVE', freeze_behavior: 'HOLD', freeze_reason: 'Quarter close' }),
      rung('deploy', { blocker: 'NO_DATASOURCE', datasource_id: null }),
      rung('qa', { blocker: 'DATASOURCE_MISSING' }),
    ]);

    expect(screen.getByTestId('ladder-reason-staging').textContent).toContain('Blocked — dev not yet applied');
    expect(screen.getByTestId('ladder-reason-prod').textContent).toContain(
      'Blocked — freeze window active (Hold releases): Quarter close',
    );
    expect(screen.getByTestId('ladder-reason-deploy').textContent).toContain('No datasource bound');
    expect(screen.getByTestId('ladder-reason-qa').textContent).toContain('no longer exists');
    expect(screen.queryByTestId('ladder-reason-dev')).toBeNull();
  });

  it('offers promotion only on the promotable rung and confirms before promoting', async () => {
    const { onPromote } = renderLadder([
      rung('dev', { state: 'PROMOTABLE' }),
      rung('prod', { blocker: 'LOWER_ENVIRONMENT_NOT_APPLIED', blocking_environment_name: 'dev' }),
    ]);

    expect(screen.queryByText('Promote to prod')).toBeNull();
    fireEvent.click(screen.getByText('Promote to dev'));
    fireEvent.click(await screen.findByText('Promote'));
    expect(onPromote).toHaveBeenCalledWith(expect.objectContaining({ environment_id: 'e-dev' }));
  });

  it('lets an open promotion be cancelled but explains an approved one', async () => {
    const { onCancel } = renderLadder([
      rung('dev', { state: 'IN_PROGRESS', latest_promotion: promotion('IN_REVIEW') }),
      rung('prod', {
        state: 'IN_PROGRESS',
        latest_promotion: promotion('APPROVED', { id: 'p-approved', environment_id: 'e-prod' }),
      }),
    ]);

    expect(screen.getAllByText('Cancel promotion')).toHaveLength(1);
    expect(screen.getByText(/can no longer be cancelled/)).toBeTruthy();
    fireEvent.click(screen.getByText('Cancel promotion'));
    const confirm = await screen.findAllByText('Cancel promotion');
    fireEvent.click(confirm[confirm.length - 1] as HTMLElement);
    expect(onCancel).toHaveBeenCalledWith('p-IN_REVIEW');
  });

  it('shows the applied state and a failed promotion error', () => {
    renderLadder([
      rung('dev', { state: 'APPLIED', latest_promotion: promotion('APPLIED') }),
      rung('prod', {
        state: 'PROMOTABLE',
        latest_promotion: promotion('FAILED', { error_message: 'relation exists', request_group_id: null }),
      }),
    ]);

    expect(screen.getByText('Last promotion: Applied')).toBeTruthy();
    expect(screen.getByText('relation exists')).toBeTruthy();
    expect(screen.getAllByText('Open request group')).toHaveLength(1);
  });
});
