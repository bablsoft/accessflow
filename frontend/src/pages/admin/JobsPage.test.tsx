import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from 'antd';
import type { ReactNode } from 'react';
import '@/i18n';
import type { JobRegistry, ScheduledJob } from '@/types/api';

const { getJobRegistryMock, listJobExecutionsMock } = vi.hoisted(() => ({
  getJobRegistryMock: vi.fn(),
  listJobExecutionsMock: vi.fn(),
}));

vi.mock('@/api/jobs', async () => {
  const actual = await vi.importActual<typeof import('@/api/jobs')>('@/api/jobs');
  return { ...actual, getJobRegistry: getJobRegistryMock, listJobExecutions: listJobExecutionsMock };
});

const { JobsPage } = await import('./JobsPage');

function job(overrides: Partial<ScheduledJob> = {}): ScheduledJob {
  return {
    job_name: 'QueryTimeoutJob',
    declaring_class: 'com.bablsoft.accessflow.workflow.internal.scheduled.QueryTimeoutJob',
    method_name: 'run',
    module: 'workflow',
    cadence_type: 'FIXED_DELAY',
    cadence: 'PT5M',
    lock_name: 'queryTimeoutJob',
    lock_at_most_for: 'PT10M',
    registered: true,
    health: {
      last_status: 'SUCCESS',
      last_abandoned: false,
      last_started_at: '2026-09-23T10:00:00Z',
      last_finished_at: '2026-09-23T10:00:01Z',
      last_duration_ms: 250,
      consecutive_failures: 0,
      window_success_count: 12,
      window_failure_count: 0,
      window_mean_duration_ms: 200,
    },
    ...overrides,
  };
}

function registry(overrides: Partial<JobRegistry> = {}): JobRegistry {
  return { scheduling_enabled: true, recording_enabled: true, summary_window: 'PT24H', jobs: [job()], ...overrides };
}

function wrap(node: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <App>{node}</App>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe('JobsPage', () => {
  beforeEach(() => {
    getJobRegistryMock.mockReset();
    listJobExecutionsMock.mockReset();
  });

  it('lists registered jobs with module, cadence and window counts', async () => {
    getJobRegistryMock.mockResolvedValue(registry());

    render(wrap(<JobsPage />));

    expect(await screen.findByText('QueryTimeoutJob')).toBeInTheDocument();
    expect(screen.getByText('queryTimeoutJob')).toBeInTheDocument();
    expect(screen.getByText('workflow')).toBeInTheDocument();
    expect(screen.getByText('5 min')).toBeInTheDocument();
    expect(screen.getByText('Success')).toBeInTheDocument();
    expect(screen.getByText('12 ok / 0 failed')).toBeInTheDocument();
    expect(screen.getAllByText('Last 24 h').length).toBeGreaterThan(0);
  });

  it('flags consecutive failures, abandoned runs, never-run and unregistered jobs', async () => {
    getJobRegistryMock.mockResolvedValue(
      registry({
        jobs: [
          job({
            job_name: 'AuditSinkDrainJob',
            health: {
              last_status: 'FAILED',
              last_abandoned: false,
              last_error_message: 'sink down',
              consecutive_failures: 3,
              window_success_count: 0,
              window_failure_count: 3,
            },
          }),
          job({
            job_name: 'ErasureExecutionJob',
            health: { last_status: 'RUNNING', last_abandoned: true, last_started_at: '2026-09-23T09:00:00Z', consecutive_failures: 0, window_success_count: 0, window_failure_count: 0 },
          }),
          job({
            job_name: 'WeeklyDigestJob',
            health: { last_abandoned: false, consecutive_failures: 0, window_success_count: 0, window_failure_count: 0 },
          }),
          job({ job_name: 'RemovedJob', registered: false, module: null, cadence: null, cadence_type: null }),
        ],
      }),
    );

    render(wrap(<JobsPage />));

    expect(await screen.findByText('3 failed')).toBeInTheDocument();
    expect(screen.getByText('Abandoned')).toBeInTheDocument();
    expect(screen.getByText('Not run yet')).toBeInTheDocument();
    expect(screen.getByText('Not registered')).toBeInTheDocument();
  });

  it('says the scheduler is disabled instead of "no jobs"', async () => {
    getJobRegistryMock.mockResolvedValue(registry({ scheduling_enabled: false, jobs: [] }));

    render(wrap(<JobsPage />));

    expect(await screen.findByText('Scheduler disabled')).toBeInTheDocument();
    expect(screen.queryByText('No scheduled jobs are registered')).not.toBeInTheDocument();
  });

  it('shows the plain empty state and the recording-off warning', async () => {
    getJobRegistryMock.mockResolvedValue(registry({ jobs: [], recording_enabled: false }));

    render(wrap(<JobsPage />));

    expect(await screen.findByText('No scheduled jobs are registered')).toBeInTheDocument();
    expect(screen.getByText(/Execution recording is off/)).toBeInTheDocument();
  });

  it('warns when jobs are registered although the switch is off', async () => {
    getJobRegistryMock.mockResolvedValue(registry({ scheduling_enabled: false }));

    render(wrap(<JobsPage />));

    expect(await screen.findByText(/The scheduling switch is off/)).toBeInTheDocument();
    expect(screen.getByText('QueryTimeoutJob')).toBeInTheDocument();
  });

  it('shows the load error', async () => {
    getJobRegistryMock.mockRejectedValue(new Error('boom'));

    render(wrap(<JobsPage />));

    expect(await screen.findByText('Could not load the scheduled jobs')).toBeInTheDocument();
  });

  it('opens the execution history drawer from a row', async () => {
    getJobRegistryMock.mockResolvedValue(registry());
    listJobExecutionsMock.mockResolvedValue({ content: [], page: 0, size: 20, total_elements: 0, total_pages: 0 });

    render(wrap(<JobsPage />));

    fireEvent.click(await screen.findByText('QueryTimeoutJob'));

    expect(await screen.findByText('QueryTimeoutJob — execution history')).toBeInTheDocument();
    expect(await screen.findByText('No recorded executions')).toBeInTheDocument();
    expect(listJobExecutionsMock).toHaveBeenCalledWith('QueryTimeoutJob', { status: undefined, page: 0, size: 20 });
  });
});
