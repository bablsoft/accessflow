import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, renderHook, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import '@/i18n';
import type { PendingReviewItem } from '@/types/api';
import { QueryReviewPreview, TriageShortcuts, nextActiveId, useTriageKeys } from '..';

const ids = ['a', 'b', 'c'];

function setup(activeId: string | null, extra: Partial<Parameters<typeof useTriageKeys>[0]> = {}) {
  const handlers = {
    onActiveChange: vi.fn(),
    onOpen: vi.fn(),
    onApprove: vi.fn(),
    onReject: vi.fn(),
    onHelp: vi.fn(),
  };
  const view = renderHook(() => useTriageKeys({ ids, activeId, ...handlers, ...extra }));
  return { ...handlers, ...view };
}

const press = (key: string, init: KeyboardEventInit = {}, target: Window | Element = window) =>
  fireEvent.keyDown(target, { key, ...init });

describe('useTriageKeys', () => {
  it('moves within bounds with J/K and the arrow keys', () => {
    const h = setup('a');
    press('j');
    expect(h.onActiveChange).toHaveBeenLastCalledWith('b');
    press('ArrowDown');
    expect(h.onActiveChange).toHaveBeenLastCalledWith('b');
    press('k');
    expect(h.onActiveChange).toHaveBeenLastCalledWith('a');
    press('ArrowUp');
    expect(h.onActiveChange).toHaveBeenLastCalledWith('a');
    h.unmount();
    const last = setup('c');
    press('J');
    expect(last.onActiveChange).toHaveBeenLastCalledWith('c');
  });

  it('selects the first row when nothing is selected, and nothing in an empty list', () => {
    const h = setup(null);
    press('K');
    expect(h.onActiveChange).toHaveBeenCalledWith('a');
    press('Enter');
    press('a');
    press('r');
    expect(h.onOpen).not.toHaveBeenCalled();
    expect(h.onApprove).not.toHaveBeenCalled();
    expect(h.onReject).not.toHaveBeenCalled();
    h.unmount();
    const empty = setup(null, { ids: [] });
    press('j');
    expect(empty.onActiveChange).not.toHaveBeenCalled();
  });

  it('runs the action keys on the selected row', () => {
    const h = setup('b');
    press('Enter');
    press('A');
    press('R');
    press('?');
    press('x');
    expect(h.onOpen).toHaveBeenCalledWith('b');
    expect(h.onApprove).toHaveBeenCalledWith('b');
    expect(h.onReject).toHaveBeenCalledWith('b');
    expect(h.onHelp).toHaveBeenCalledTimes(1);
  });

  it('ignores chords, typing, open dialogs and a disabled hook', () => {
    const h = setup('a');
    press('a', { ctrlKey: true });
    press('a', { metaKey: true });
    const input = document.createElement('input');
    document.body.appendChild(input);
    press('a', {}, input);
    const modal = document.createElement('div');
    modal.className = 'ant-modal-wrap';
    document.body.appendChild(modal);
    press('a');
    expect(h.onApprove).not.toHaveBeenCalled();
    modal.remove();
    input.remove();
    press('a');
    expect(h.onApprove).toHaveBeenCalledTimes(1);
    h.unmount();
    const off = setup('a', { enabled: false });
    press('a');
    expect(off.onApprove).not.toHaveBeenCalled();
  });

  it('skips keys whose handler is not provided', () => {
    const h = setup('a', { onOpen: undefined, onApprove: undefined, onReject: undefined, onHelp: undefined });
    press('Enter');
    press('a');
    press('r');
    press('?');
    expect(h.onActiveChange).not.toHaveBeenCalled();
  });
});

describe('nextActiveId', () => {
  it('prefers the following row, then the preceding one', () => {
    expect(nextActiveId(ids, 'a')).toBe('b');
    expect(nextActiveId(ids, 'c')).toBe('b');
    expect(nextActiveId(['a'], 'a')).toBeNull();
    expect(nextActiveId(ids, 'zzz')).toBeNull();
  });
});

describe('TriageShortcuts', () => {
  it('lists decision keys by default and omits them on request', () => {
    const { rerender } = render(<TriageShortcuts open onOpenChange={vi.fn()} />);
    expect(screen.getByText('Approve')).toBeInTheDocument();
    expect(screen.getByText('Next request')).toBeInTheDocument();
    rerender(<TriageShortcuts open onOpenChange={vi.fn()} decisions={false} />);
    expect(screen.queryByText('Approve')).toBeNull();
  });
});

describe('QueryReviewPreview', () => {
  const item: PendingReviewItem = {
    id: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
    datasource: { id: 'ds', name: 'orders-db' },
    submitted_by: { id: 'u', email: 'sub@example.com' },
    sql_text: 'SELECT 1',
    query_type: 'SELECT',
    justification: 'monthly report',
    ai_analysis: { id: 'ai', risk_level: 'HIGH', risk_score: 80, summary: 'Reads a large table' },
    approval_probability: 0.9,
    sql_review_blocking_count: 2,
    current_stage: 1,
    created_at: '2026-05-01T10:00:00Z',
  };

  const show = (value: PendingReviewItem | null, onApprove = vi.fn(), onReject = vi.fn()) =>
    render(
      <MemoryRouter>
        <QueryReviewPreview item={value} onApprove={onApprove} onReject={onReject} />
      </MemoryRouter>,
    );

  it('prompts for a selection when there is none', () => {
    show(null);
    expect(screen.getByText('Nothing selected')).toBeInTheDocument();
  });

  it('shows the request and decides from the panel', () => {
    const onApprove = vi.fn();
    const onReject = vi.fn();
    show(item, onApprove, onReject);
    expect(screen.getByText('aaaaaaaa')).toBeInTheDocument();
    expect(screen.getByText('monthly report')).toBeInTheDocument();
    expect(screen.getByText('Reads a large table')).toBeInTheDocument();
    expect(screen.getByText('SQL review')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /Approve/ }));
    fireEvent.click(screen.getByRole('button', { name: /Reject/ }));
    expect(onApprove).toHaveBeenCalledWith(item.id);
    expect(onReject).toHaveBeenCalledWith(item.id);
  });

  it('omits the optional sections when the request has none', () => {
    show({ ...item, justification: '', ai_analysis: null, approval_probability: null, sql_review_blocking_count: 0 });
    expect(screen.queryByText('Justification')).toBeNull();
    expect(screen.queryByText('AI summary')).toBeNull();
    expect(screen.queryByText('SQL review')).toBeNull();
  });
});
