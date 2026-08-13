import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import '@/i18n';
import { ActivityList } from './ActivityList';

interface Row {
  id: string;
  name: string;
}

function renderList(props: Partial<Parameters<typeof ActivityList<Row>>[0]> = {}) {
  return render(
    <MemoryRouter>
      <ActivityList<Row>
        items={[]}
        loading={false}
        emptyTitle="Nothing here"
        rowKey={(r) => r.id}
        renderRow={(r) => ({ primary: r.name, meta: 'just now' })}
        {...props}
      />
    </MemoryRouter>,
  );
}

const rows: Row[] = Array.from({ length: 7 }, (_, i) => ({ id: `r${i}`, name: `Row ${i}` }));

describe('ActivityList', () => {
  it('shows a skeleton while loading', () => {
    const { container } = renderList({ loading: true });
    expect(container.querySelector('.ant-skeleton')).not.toBeNull();
  });

  it('shows the error block with retry instead of the empty state', () => {
    const onRetry = vi.fn();
    renderList({ error: new Error('boom'), onRetry });
    expect(screen.getByRole('alert')).toBeInTheDocument();
    expect(screen.queryByText('Nothing here')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /retry/i }));
    expect(onRetry).toHaveBeenCalled();
  });

  it('shows the compact empty state when there are no items', () => {
    renderList();
    expect(screen.getByText('Nothing here')).toBeInTheDocument();
  });

  it('renders at most maxRows rows', () => {
    renderList({ items: rows });
    expect(screen.getByText('Row 0')).toBeInTheDocument();
    expect(screen.getByText('Row 4')).toBeInTheDocument();
    expect(screen.queryByText('Row 5')).not.toBeInTheDocument();
  });

  it('renders pills, meta, action and the view-all footer link', () => {
    renderList({
      items: rows.slice(0, 1),
      viewAllTo: '/queries',
      renderRow: (r) => ({
        pills: <span data-testid="pill">P</span>,
        primary: r.name,
        meta: 'just now',
        action: <a href="/x">Open</a>,
      }),
    });
    expect(screen.getByTestId('pill')).toBeInTheDocument();
    expect(screen.getByText('just now')).toBeInTheDocument();
    expect(screen.getByText('Open')).toBeInTheDocument();
    const viewAll = screen.getByRole('link', { name: /view all/i });
    expect(viewAll).toHaveAttribute('href', '/queries');
  });
});
