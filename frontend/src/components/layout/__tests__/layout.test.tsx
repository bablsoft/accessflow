import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { App } from 'antd';
import {
  DataTable,
  ErrorBoundary,
  FilterBar,
  Page,
  PageBody,
  RouteFallback,
  Section,
  StatusTag,
  SurfaceCard,
  useConfirm,
} from '..';

interface Row {
  id: string;
  name: string;
}
const rows: Row[] = [
  { id: '1', name: 'alpha' },
  { id: '2', name: 'beta' },
];
const columns = [
  { title: 'Name', dataIndex: 'name' },
  {
    title: 'Action',
    render: () => <button type="button">act</button>,
  },
];

describe('Page / PageBody', () => {
  it('applies the width and flush variants', () => {
    const { container } = render(
      <Page>
        <PageBody width="form" flush>
          body
        </PageBody>
      </Page>,
    );
    const body = container.querySelector('.af-page-body');
    expect(container.firstElementChild).toHaveClass('af-page');
    expect(body).toHaveClass('af-page-body--form', 'af-page-body--flush');
    expect(screen.getByText('body')).toBeInTheDocument();
  });

  it('defaults to full width with padding', () => {
    const { container } = render(<PageBody>body</PageBody>);
    const body = container.querySelector('.af-page-body');
    expect(body).toHaveClass('af-page-body--full');
    expect(body).not.toHaveClass('af-page-body--flush');
  });
});

describe('FilterBar', () => {
  it('renders a search landmark with search input and count', () => {
    const onChange = vi.fn();
    render(
      <FilterBar>
        <FilterBar.Search placeholder="Find" className="extra" onChange={onChange} />
        <FilterBar.Count>2 of 9</FilterBar.Count>
      </FilterBar>,
    );
    const bar = screen.getByRole('search');
    fireEvent.change(within(bar).getByPlaceholderText('Find'), { target: { value: 'x' } });
    expect(onChange).toHaveBeenCalled();
    expect(bar.querySelector('.af-filter-bar-search')).toHaveClass('extra');
    expect(screen.getByText('2 of 9')).toBeInTheDocument();
  });
});

describe('DataTable', () => {
  it('shows a skeleton while loading', () => {
    const { container } = render(<DataTable<Row> skeleton rowKey="id" columns={columns} />);
    expect(container.querySelector('[aria-busy="true"]')).toBeInTheDocument();
    expect(screen.queryByText('Name')).not.toBeInTheDocument();
  });

  it('renders the custom empty state', () => {
    render(
      <DataTable<Row>
        rowKey="id"
        columns={columns}
        dataSource={[]}
        emptyTitle="Nothing yet"
        emptyDescription="Add one"
      />,
    );
    expect(screen.getByText('Nothing yet')).toBeInTheDocument();
    expect(screen.getByText('Add one')).toBeInTheDocument();
  });

  it('activates a row by click and Enter, but not from an inner control', () => {
    const onRowActivate = vi.fn();
    const baseClick = vi.fn();
    render(
      <DataTable<Row>
        rowKey="id"
        columns={columns}
        dataSource={rows}
        onRowActivate={onRowActivate}
        onRow={() => ({ onClick: baseClick, className: 'mine' })}
      />,
    );
    const row = screen.getByText('alpha').closest('tr') as HTMLElement;
    expect(row).toHaveClass('af-row-clickable', 'mine');
    expect(row).toHaveAttribute('tabindex', '0');

    fireEvent.click(screen.getByText('alpha'));
    expect(onRowActivate).toHaveBeenCalledWith(rows[0]);
    expect(baseClick).toHaveBeenCalledTimes(1);

    fireEvent.keyDown(row, { key: 'Enter' });
    expect(onRowActivate).toHaveBeenCalledTimes(2);
    fireEvent.keyDown(row, { key: 'a' });
    expect(onRowActivate).toHaveBeenCalledTimes(2);

    fireEvent.click(within(row).getByRole('button', { name: 'act' }));
    expect(onRowActivate).toHaveBeenCalledTimes(2);
  });

  it('leaves rows inert without onRowActivate and passes onRow through', () => {
    render(
      <DataTable<Row>
        rowKey="id"
        columns={columns}
        dataSource={rows}
        onRow={() => ({ className: 'plain' })}
      />,
    );
    const row = screen.getByText('alpha').closest('tr') as HTMLElement;
    expect(row).toHaveClass('plain');
    expect(row).not.toHaveAttribute('tabindex');
  });

  it('passes the pagination prop through when no page is given', () => {
    const { container } = render(
      <DataTable<Row> rowKey="id" columns={columns} dataSource={rows} pagination={false} />,
    );
    expect(container.querySelector('.ant-pagination')).toBeNull();
  });

  it('maps zero-based server pagination', () => {
    const onPageChange = vi.fn();
    render(
      <DataTable<Row>
        rowKey="id"
        columns={columns}
        dataSource={rows}
        page={0}
        pageSize={2}
        total={6}
        onPageChange={onPageChange}
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Page 3' }));
    expect(onPageChange).toHaveBeenCalledWith(2);
  });
});

describe('SurfaceCard / Section', () => {
  it('renders header slots and padded body', () => {
    const { container } = render(
      <SurfaceCard title="Card" icon={<i data-testid="icon" />} extra={<b>more</b>} padded>
        inside
      </SurfaceCard>,
    );
    expect(screen.getByText('Card')).toBeInTheDocument();
    expect(screen.getByTestId('icon')).toBeInTheDocument();
    expect(screen.getByText('more')).toBeInTheDocument();
    expect(container.querySelector('.af-surface-body--padded')).toHaveTextContent('inside');
  });

  it('omits the header when no header slot is given', () => {
    const { container } = render(<SurfaceCard>bare</SurfaceCard>);
    expect(container.querySelector('.af-surface-header')).toBeNull();
  });

  it('renders a titled section with a description', () => {
    render(
      <Section title="Identity" description="Who signs in">
        fields
      </Section>,
    );
    expect(screen.getByRole('heading', { level: 2, name: 'Identity' })).toBeInTheDocument();
    expect(screen.getByText('Who signs in')).toBeInTheDocument();
    expect(screen.getByText('fields')).toBeInTheDocument();
  });
});

describe('StatusTag', () => {
  it('colours by tone', () => {
    render(
      <>
        <StatusTag tone="danger">Failed</StatusTag>
        <StatusTag tone="accent" withDot size="sm">New</StatusTag>
        <StatusTag>Idle</StatusTag>
      </>,
    );
    expect(screen.getByText('Failed').style.color).toBe('var(--risk-crit)');
    expect(screen.getByText('New')).toHaveClass('af-pill-sm');
    expect(screen.getByText('Idle').style.color).toBe('var(--status-neutral)');
  });
});

describe('useConfirm', () => {
  function Harness({ onOk, okText }: { onOk: () => void; okText?: string }) {
    const confirm = useConfirm();
    return (
      <button type="button" onClick={() => confirm({ title: 'Remove it?', okText, onOk })}>
        open
      </button>
    );
  }

  it('opens a danger confirmation and runs onOk', async () => {
    const onOk = vi.fn();
    render(
      <App>
        <Harness onOk={onOk} okText="Remove" />
      </App>,
    );
    fireEvent.click(screen.getByText('open'));
    expect((await screen.findAllByText('Remove it?')).length).toBeGreaterThan(0);
    const ok = screen.getByText('Remove').closest('button') as HTMLElement;
    expect(ok).toHaveClass('ant-btn-dangerous');
    fireEvent.click(ok);
    expect(onOk).toHaveBeenCalled();
  });

  it('defaults the confirm label', async () => {
    render(
      <App>
        <Harness onOk={vi.fn()} />
      </App>,
    );
    fireEvent.click(screen.getByText('open'));
    expect((await screen.findAllByText('Remove it?')).length).toBeGreaterThan(0);
    expect(document.querySelector('.ant-btn-dangerous')).toBeInTheDocument();
  });
});

describe('RouteFallback', () => {
  it('announces a busy region', () => {
    const { container } = render(<RouteFallback />);
    expect(container.firstElementChild).toHaveAttribute('aria-busy', 'true');
  });
});

describe('ErrorBoundary', () => {
  function Boom({ fail }: { fail: boolean }) {
    if (fail) throw new Error('boom');
    return <div>fine</div>;
  }

  it('shows a fallback, retries, and resets when the key changes', () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const { rerender } = render(
      <ErrorBoundary resetKey="/a">
        <Boom fail />
      </ErrorBoundary>,
    );
    expect(screen.getByRole('alert')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button'));
    expect(screen.getByRole('alert')).toBeInTheDocument();

    rerender(
      <ErrorBoundary resetKey="/b">
        <Boom fail={false} />
      </ErrorBoundary>,
    );
    expect(screen.getByText('fine')).toBeInTheDocument();
    spy.mockRestore();
  });
});
