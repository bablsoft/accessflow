import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import '@/i18n';

const { mergeViewMock } = vi.hoisted(() => ({ mergeViewMock: vi.fn() }));

vi.mock('@codemirror/merge', () => ({
  MergeView: class {
    constructor(config: unknown) {
      mergeViewMock(config);
    }
    destroy() {}
  },
}));

import { QuerySqlView } from './QuerySqlView';

const SUBMITTED = 'SELECT * FROM orders';
const EFFECTIVE = 'SELECT * FROM (SELECT * FROM orders WHERE orders.region = ?) orders';

describe('QuerySqlView', () => {
  beforeEach(() => {
    mergeViewMock.mockReset();
  });

  it('renders only the submitted SQL when no effective statement was recorded', () => {
    const { container } = render(<QuerySqlView sql={SUBMITTED} effectiveSql={null} />);

    expect(container.querySelector('pre')?.textContent).toBe(SUBMITTED);
    expect(screen.queryByTestId('query-effective-sql')).not.toBeInTheDocument();
    expect(screen.queryByText('Effective')).not.toBeInTheDocument();
  });

  it('switches between submitted, effective and diff views', () => {
    const { container } = render(
      <QuerySqlView sql={SUBMITTED} effectiveSql={EFFECTIVE} dbType="POSTGRESQL" />,
    );

    expect(container.querySelector('pre')?.textContent).toBe(SUBMITTED);
    expect(screen.getByText(/Bound values are shown as \?/)).toBeInTheDocument();

    fireEvent.click(screen.getByText('Effective'));
    expect(container.querySelector('pre')?.textContent).toBe(EFFECTIVE);

    fireEvent.click(screen.getByText('Diff'));
    expect(screen.getByTestId('sql-diff-view')).toBeInTheDocument();
    expect(container.querySelector('pre')).toBeNull();
    const config = mergeViewMock.mock.calls[0]?.[0] as { a: { doc: string }; b: { doc: string } };
    expect(config.a.doc).toBe(SUBMITTED);
    expect(config.b.doc).toBe(EFFECTIVE);
  });
});
