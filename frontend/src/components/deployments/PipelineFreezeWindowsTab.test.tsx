import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App as AntdApp } from 'antd';
import type { ReactNode } from 'react';
import '@/i18n';
import type { DeploymentFreezeWindow, DeploymentFreezeWindowPage } from '@/types/api';

const {
  listDeploymentFreezeWindows,
  createDeploymentFreezeWindow,
  updateDeploymentFreezeWindow,
  deleteDeploymentFreezeWindow,
  listDeploymentEnvironments,
} = vi.hoisted(() => ({
  listDeploymentFreezeWindows: vi.fn(),
  createDeploymentFreezeWindow: vi.fn(),
  updateDeploymentFreezeWindow: vi.fn(),
  deleteDeploymentFreezeWindow: vi.fn(),
  listDeploymentEnvironments: vi.fn(),
}));

vi.mock('@/api/deploymentFreezeWindows', async () => {
  const actual = await vi.importActual<typeof import('@/api/deploymentFreezeWindows')>(
    '@/api/deploymentFreezeWindows',
  );
  return {
    ...actual,
    listDeploymentFreezeWindows,
    createDeploymentFreezeWindow,
    updateDeploymentFreezeWindow,
    deleteDeploymentFreezeWindow,
  };
});

vi.mock('@/api/deploymentPipelines', async () => {
  const actual = await vi.importActual<typeof import('@/api/deploymentPipelines')>(
    '@/api/deploymentPipelines',
  );
  return { ...actual, listDeploymentEnvironments };
});

const { PipelineFreezeWindowsTab } = await import('./PipelineFreezeWindowsTab');

const oneOffWindow: DeploymentFreezeWindow = {
  id: 'fw-1',
  pipeline_id: 'pipe-1',
  environment_id: null,
  starts_at: '2026-12-24T00:00:00Z',
  ends_at: '2026-12-27T00:00:00Z',
  days_of_week: [],
  start_time: null,
  end_time: null,
  timezone: null,
  behavior: 'HOLD',
  reason: 'Release freeze',
  enabled: true,
  created_at: '2026-08-01T10:00:00Z',
};

const globalRecurringWindow: DeploymentFreezeWindow = {
  id: 'fw-2',
  pipeline_id: null,
  environment_id: null,
  starts_at: null,
  ends_at: null,
  days_of_week: [6, 7],
  start_time: '08:00:00',
  end_time: '10:00:00',
  timezone: 'UTC',
  behavior: 'REJECT',
  reason: 'Weekend freeze',
  enabled: true,
  created_at: '2026-08-01T10:00:00Z',
};

const otherPipelineWindow: DeploymentFreezeWindow = {
  ...oneOffWindow,
  id: 'fw-3',
  pipeline_id: 'other-pipe',
  reason: 'Other pipeline freeze',
};

function page(content: DeploymentFreezeWindow[]): DeploymentFreezeWindowPage {
  return { content, page: 0, size: 200, total_elements: content.length, total_pages: 1 };
}

function wrap(node: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={client}>
      <AntdApp>{node}</AntdApp>
    </QueryClientProvider>
  );
}

function selectOption(text: string) {
  const option = [...document.querySelectorAll('.ant-select-item-option-content')].find(
    (o) => o.textContent === text,
  );
  expect(option).toBeDefined();
  fireEvent.click(option!);
}

describe('PipelineFreezeWindowsTab', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    listDeploymentFreezeWindows.mockResolvedValue(
      page([oneOffWindow, globalRecurringWindow, otherPipelineWindow]),
    );
    listDeploymentEnvironments.mockResolvedValue([]);
    createDeploymentFreezeWindow.mockResolvedValue(oneOffWindow);
    updateDeploymentFreezeWindow.mockResolvedValue(oneOffWindow);
    deleteDeploymentFreezeWindow.mockResolvedValue(undefined);
  });

  it('shows this pipeline plus global windows, tags globals, and filters other pipelines out', async () => {
    render(wrap(<PipelineFreezeWindowsTab pipelineId="pipe-1" />));

    await screen.findByText('Release freeze');
    expect(screen.getByText('Weekend freeze')).toBeInTheDocument();
    // The null-pipeline row carries the Global tag.
    expect(screen.getByText('Global')).toBeInTheDocument();
    // A window scoped to another pipeline is not listed.
    expect(screen.queryByText('Other pipeline freeze')).not.toBeInTheDocument();
  });

  it('blocks a one-off submit without dates — create is never called', async () => {
    render(wrap(<PipelineFreezeWindowsTab pipelineId="pipe-1" />));
    await screen.findByText('Release freeze');

    fireEvent.click(screen.getByRole('button', { name: /add freeze window/i }));
    await screen.findByRole('dialog');

    // Mode defaults to one-off: the date fields are required and empty.
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() =>
      expect(document.querySelectorAll('.ant-form-item-explain-error').length).toBeGreaterThan(0),
    );
    expect(createDeploymentFreezeWindow).not.toHaveBeenCalled();
  });

  it('creates a recurring window with days, HH:mm times, timezone, and null one-off bounds', async () => {
    render(wrap(<PipelineFreezeWindowsTab pipelineId="pipe-1" />));
    await screen.findByText('Release freeze');

    fireEvent.click(screen.getByRole('button', { name: /add freeze window/i }));
    await screen.findByRole('dialog');

    fireEvent.click(screen.getByText('Weekly recurring'));

    // Days of week (multiple select) appears once the watched mode flips to recurring.
    const daysSelect = await screen.findByLabelText('Days of week');
    fireEvent.mouseDown(daysSelect);
    await waitFor(() =>
      expect(
        [...document.querySelectorAll('.ant-select-item-option-content')].length,
      ).toBeGreaterThan(0),
    );
    selectOption('Monday');

    // Times typed into the pickers and committed with Enter.
    const startTime = screen.getByLabelText('Start time');
    fireEvent.mouseDown(startTime);
    fireEvent.change(startTime, { target: { value: '08:00' } });
    fireEvent.keyDown(startTime, { key: 'Enter', code: 'Enter' });
    const endTime = screen.getByLabelText('End time');
    fireEvent.mouseDown(endTime);
    fireEvent.change(endTime, { target: { value: '10:00' } });
    fireEvent.keyDown(endTime, { key: 'Enter', code: 'Enter' });

    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(createDeploymentFreezeWindow).toHaveBeenCalledTimes(1));
    const [input] = createDeploymentFreezeWindow.mock.calls[0]!;
    expect(input).toEqual({
      // Scope defaults to this pipeline; no environment picked.
      pipeline_id: 'pipe-1',
      environment_id: null,
      // toWireInput nulls the one-off half in recurring mode.
      starts_at: null,
      ends_at: null,
      days_of_week: [1],
      start_time: '08:00',
      end_time: '10:00',
      timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
      behavior: 'HOLD',
      reason: null,
      enabled: true,
    });
  });

  it('renders the week strip only when an enabled recurring window exists', async () => {
    const { unmount } = render(wrap(<PipelineFreezeWindowsTab pipelineId="pipe-1" />));
    await screen.findByText('Weekend freeze');
    expect(screen.getByTestId('freeze-week-strip')).toBeInTheDocument();
    unmount();

    listDeploymentFreezeWindows.mockResolvedValue(page([oneOffWindow]));
    render(wrap(<PipelineFreezeWindowsTab pipelineId="pipe-1" />));
    await screen.findByText('Release freeze');
    expect(screen.queryByTestId('freeze-week-strip')).not.toBeInTheDocument();
  });

  it('deletes a window after confirming the Popconfirm', async () => {
    render(wrap(<PipelineFreezeWindowsTab pipelineId="pipe-1" />));
    await screen.findByText('Release freeze');

    const rowDeletes = screen.getAllByRole('button', { name: 'Delete' });
    fireEvent.click(rowDeletes[0]!);

    await screen.findByText('Delete this freeze window?');
    const allDeletes = screen.getAllByRole('button', { name: 'Delete' });
    fireEvent.click(allDeletes[allDeletes.length - 1]!);

    await waitFor(() => expect(deleteDeploymentFreezeWindow).toHaveBeenCalledTimes(1));
    expect(deleteDeploymentFreezeWindow).toHaveBeenCalledWith('fw-1');
  });
});
