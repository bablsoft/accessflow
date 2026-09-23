import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from 'antd';
import type { ReactNode } from 'react';
import '@/i18n';
import type { JobExecution } from '@/types/api';

const { listJobExecutionsMock } = vi.hoisted(() => ({ listJobExecutionsMock: vi.fn() }));

vi.mock('@/api/jobs', async () => {
  const actual = await vi.importActual<typeof import('@/api/jobs')>('@/api/jobs');
  return { ...actual, listJobExecutions: listJobExecutionsMock };
});

const { JobExecutionsDrawer } = await import('./JobExecutionsDrawer');

function execution(overrides: Partial<JobExecution> = {}): JobExecution {
  return {
    id: 'e1',
    job_name: 'QueryTimeoutJob',
    lock_name: 'queryTimeoutJob',
    instance_id: 'pod-1',
    started_at: '2026-09-23T10:00:00Z',
    finished_at: '2026-09-23T10:00:01Z',
    duration_ms: 250,
    status: 'SUCCESS',
    abandoned: false,
    ...overrides,
  };
}

function wrap(node: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={client}>
      <App>{node}</App>
    </QueryClientProvider>
  );
}

describe('JobExecutionsDrawer', () => {
  beforeEach(() => {
    listJobExecutionsMock.mockReset();
  });

  it('renders nothing when no job is selected', () => {
    render(wrap(<JobExecutionsDrawer jobName={null} onClose={() => undefined} />));
    expect(listJobExecutionsMock).not.toHaveBeenCalled();
  });

  it('lists executions with status, instance and the error of a failed run', async () => {
    listJobExecutionsMock.mockResolvedValue({
      content: [
        execution(),
        execution({
          id: 'e2',
          status: 'FAILED',
          error_class: 'java.lang.IllegalStateException',
          error_message: 'db unreachable',
        }),
        execution({ id: 'e3', status: 'RUNNING', abandoned: true, duration_ms: null, instance_id: null }),
      ],
      page: 0,
      size: 20,
      total_elements: 3,
      total_pages: 1,
    });

    render(wrap(<JobExecutionsDrawer jobName="QueryTimeoutJob" onClose={() => undefined} />));

    expect(await screen.findByText('QueryTimeoutJob — execution history')).toBeInTheDocument();
    expect(await screen.findByText('Failed')).toBeInTheDocument();
    expect(screen.getByText('Success')).toBeInTheDocument();
    expect(screen.getByText('Abandoned')).toBeInTheDocument();
    expect(screen.getAllByText('pod-1').length).toBeGreaterThan(0);
    expect(listJobExecutionsMock).toHaveBeenCalledWith('QueryTimeoutJob', { status: undefined, page: 0, size: 20 });

    const expand = document.querySelectorAll('.ant-table-row-expand-icon:not(.ant-table-row-expand-icon-spaced)');
    expect(expand.length).toBe(1);
    fireEvent.click(expand[0] as Element);
    expect(await screen.findByText('db unreachable')).toBeInTheDocument();
    expect(screen.getByText('java.lang.IllegalStateException')).toBeInTheDocument();
  });

  it('shows the empty state when the job has no history', async () => {
    listJobExecutionsMock.mockResolvedValue({ content: [], page: 0, size: 20, total_elements: 0, total_pages: 0 });

    render(wrap(<JobExecutionsDrawer jobName="QueryTimeoutJob" onClose={() => undefined} />));

    expect(await screen.findByText('No recorded executions')).toBeInTheDocument();
  });

  it('shows the server error rather than an empty history on failure', async () => {
    listJobExecutionsMock.mockRejectedValue(new Error('boom'));

    render(wrap(<JobExecutionsDrawer jobName="QueryTimeoutJob" onClose={() => undefined} />));

    await waitFor(() =>
      expect(screen.getAllByText('Could not load the execution history').length).toBeGreaterThan(0),
    );
    expect(screen.queryByText('No recorded executions')).not.toBeInTheDocument();
  });

  it('sends the chosen status, resets to the first page, and converts page numbers', async () => {
    listJobExecutionsMock.mockImplementation((_job: string, params: { page: number }) =>
      Promise.resolve({
        content: [execution({ id: `e-${params.page}` })],
        page: params.page,
        size: 20,
        total_elements: 45,
        total_pages: 3,
      }),
    );

    render(wrap(<JobExecutionsDrawer jobName="QueryTimeoutJob" onClose={() => undefined} />));
    await screen.findByText('Success');

    const pager = document.querySelector('.ant-pagination') as HTMLElement;
    fireEvent.click(within(pager).getByText('2'));
    await waitFor(() =>
      expect(listJobExecutionsMock).toHaveBeenLastCalledWith('QueryTimeoutJob', {
        status: undefined,
        page: 1,
        size: 20,
      }),
    );

    fireEvent.mouseDown(document.querySelector('.ant-select-selector, .ant-select') as Element);
    fireEvent.click(await screen.findByTitle('Failed'));
    await waitFor(() =>
      expect(listJobExecutionsMock).toHaveBeenLastCalledWith('QueryTimeoutJob', {
        status: 'FAILED',
        page: 0,
        size: 20,
      }),
    );
  });
});
