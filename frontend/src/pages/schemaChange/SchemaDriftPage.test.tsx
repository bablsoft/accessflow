import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App as AntdApp } from 'antd';
import { AxiosError, AxiosHeaders } from 'axios';
import '@/i18n';
import type { SchemaChangePipeline, SchemaDriftFinding, SchemaDriftScan } from '@/types/api';

const api = vi.hoisted(() => ({
  listSchemaChangePipelines: vi.fn(),
  listSchemaDriftFindings: vi.fn(),
  listSchemaDriftScans: vi.fn(),
  requestSchemaDriftScan: vi.fn(),
  acknowledgeSchemaDriftFinding: vi.fn(),
}));

vi.mock('@/api/schemaChange', async () => {
  const actual = await vi.importActual<typeof import('@/api/schemaChange')>('@/api/schemaChange');
  return { schemaChangeKeys: actual.schemaChangeKeys, ...api };
});

const Page = (await import('./SchemaDriftPage')).default;

const pipelines: SchemaChangePipeline[] = [
  {
    id: 'p-1',
    name: 'orders',
    active: true,
    environments: [
      { id: 'e-dev', name: 'dev', sort_order: 0, datasource_id: 'd-1' },
      { id: 'e-deploy', name: 'deploy-only', sort_order: 1, datasource_id: null },
      { id: 'e-prod', name: 'prod', sort_order: 2, datasource_id: 'd-2' },
      { id: 'e-mongo', name: 'mongo', sort_order: 3, datasource_id: 'd-3' },
    ],
  },
];

const scan = (environment: string, extra: Partial<SchemaDriftScan> = {}): SchemaDriftScan => ({
  id: `sc-${environment}`,
  pipeline_id: 'p-1',
  environment_id: environment,
  baseline: 'PREVIOUS_ENVIRONMENT',
  started_at: '2026-09-23T10:00:00Z',
  finished_at: '2026-09-23T10:00:30Z',
  applicable: true,
  findings_count: 0,
  partial: false,
  ...extra,
});

const finding: SchemaDriftFinding = {
  id: 'f-1',
  scan_id: 'sc-e-prod',
  environment_id: 'e-prod',
  object_path: 'public.orders.email',
  finding_kind: 'NULLABILITY_MISMATCH',
  expected_value: 'NOT NULL',
  actual_value: 'NULL',
  status: 'OPEN',
  first_detected_at: '2026-09-01T10:00:00Z',
  last_seen_at: '2026-09-23T10:00:00Z',
};

const page = <T,>(content: T[]) => ({ content, page: 0, size: 100, total_elements: content.length, total_pages: 1 });

function problem(status: number, data: unknown) {
  return new AxiosError('Request failed', 'ERR_BAD_RESPONSE', undefined, undefined, {
    status,
    statusText: 'x',
    headers: {},
    config: { headers: new AxiosHeaders() },
    data,
  });
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <AntdApp>
        <MemoryRouter>
          <Page />
        </MemoryRouter>
      </AntdApp>
    </QueryClientProvider>,
  );
}

describe('SchemaDriftPage', () => {
  beforeEach(() => {
    Object.values(api).forEach((fn) => fn.mockReset());
    api.listSchemaChangePipelines.mockResolvedValue(pipelines);
    api.listSchemaDriftFindings.mockResolvedValue(page([finding]));
    api.listSchemaDriftScans.mockResolvedValue(
      page([
        scan('e-prod', { findings_count: 1 }),
        scan('e-mongo', { applicable: false, error_message: 'ENGINE_NOT_APPLICABLE' }),
      ]),
    );
  });

  it('shows a skeleton while loading', () => {
    api.listSchemaDriftFindings.mockReturnValue(new Promise(() => {}));
    renderPage();
    expect(screen.getByTestId('schema-drift-loading')).toBeTruthy();
  });

  it('shows the server detail when drift cannot be loaded', async () => {
    api.listSchemaDriftScans.mockRejectedValue(problem(500, { detail: 'Drift store unavailable' }));
    renderPage();
    expect(await screen.findByText('Drift store unavailable')).toBeTruthy();
  });

  it('shows an empty state when no environment binds a datasource', async () => {
    api.listSchemaChangePipelines.mockResolvedValue([
      { ...pipelines[0], environments: [{ id: 'e-x', name: 'x', sort_order: 0, datasource_id: null }] },
    ]);
    api.listSchemaDriftFindings.mockResolvedValue(page([]));
    api.listSchemaDriftScans.mockResolvedValue(page([]));
    renderPage();
    expect(await screen.findByText('No environment to scan')).toBeTruthy();
  });

  it('groups findings by environment with expected vs actual', async () => {
    renderPage();
    const prod = await screen.findByTestId('drift-environment-prod');
    expect(prod.textContent).toContain('public.orders.email');
    expect(prod.textContent).toContain('Nullability mismatch');
    expect(prod.textContent).toContain('NOT NULL');
    expect(prod.textContent).toContain('Open');
    expect(screen.queryByTestId('drift-environment-deploy-only')).toBeNull();
    expect(screen.getByTestId('drift-environment-dev').textContent).toContain('Not scanned yet.');
  });

  it('renders an inapplicable engine as unsupported, distinct from a clean scan', async () => {
    api.listSchemaDriftScans.mockResolvedValue(
      page([scan('e-dev'), scan('e-mongo', { applicable: false, error_message: 'ENGINE_NOT_APPLICABLE' })]),
    );
    api.listSchemaDriftFindings.mockResolvedValue(page([]));
    renderPage();

    const unsupported = await screen.findByTestId('drift-unsupported-mongo');
    expect(unsupported.textContent).toContain('Not supported for this engine');
    expect(unsupported.textContent).toContain('samples data rather than reading a catalog');
    expect(screen.queryByTestId('drift-clean-mongo')).toBeNull();

    const clean = screen.getByTestId('drift-clean-dev');
    expect(clean.textContent).toContain('No drift detected');
    expect(screen.queryByTestId('drift-unsupported-dev')).toBeNull();
  });

  it('explains a scan that had nothing to compare and a running scan', async () => {
    api.listSchemaDriftScans.mockResolvedValue(
      page([
        scan('e-dev', { error_message: 'BASELINE_PREVIOUS_ENVIRONMENT_NOT_FOUND' }),
        scan('e-prod', { finished_at: null }),
      ]),
    );
    api.listSchemaDriftFindings.mockResolvedValue(page([]));
    renderPage();
    expect(await screen.findByText(/No lower environment of the ladder binds a datasource/)).toBeTruthy();
    expect(screen.getByTestId('drift-environment-prod').textContent).toContain('Scan in progress');
  });

  it('acknowledges a finding and starts a scan', async () => {
    api.acknowledgeSchemaDriftFinding.mockResolvedValue({ ...finding, status: 'ACKNOWLEDGED' });
    api.requestSchemaDriftScan.mockResolvedValue(scan('e-dev', { finished_at: null }));
    renderPage();
    fireEvent.click(await screen.findByText('Acknowledge'));
    await waitFor(() => expect(api.acknowledgeSchemaDriftFinding).toHaveBeenCalledWith('f-1', expect.anything()));
    expect(await screen.findByText('Finding acknowledged')).toBeTruthy();

    fireEvent.click(screen.getAllByText('Scan now')[0] as HTMLElement);
    await waitFor(() => expect(api.requestSchemaDriftScan).toHaveBeenCalledWith('e-dev', expect.anything()));
  });

  it('surfaces the server detail when a scan is refused', async () => {
    api.requestSchemaDriftScan.mockRejectedValue(
      problem(409, { detail: 'A drift scan of this environment is already running' }),
    );
    renderPage();
    fireEvent.click((await screen.findAllByText('Scan now'))[0] as HTMLElement);
    expect(await screen.findByText('A drift scan of this environment is already running')).toBeTruthy();
  });

  it('filters by status and environment', async () => {
    renderPage();
    await screen.findByTestId('drift-environment-prod');
    fireEvent.mouseDown(screen.getByLabelText('Status'));
    fireEvent.click(await screen.findByTitle('All statuses'));
    await waitFor(() =>
      expect(api.listSchemaDriftFindings).toHaveBeenLastCalledWith(expect.not.objectContaining({ status: 'OPEN' })),
    );
    fireEvent.mouseDown(screen.getByLabelText('Environment'));
    fireEvent.click(await screen.findByTitle('orders / prod'));
    await waitFor(() =>
      expect(api.listSchemaDriftFindings).toHaveBeenLastCalledWith(
        expect.objectContaining({ environment_id: 'e-prod' }),
      ),
    );
    expect(screen.queryByTestId('drift-environment-dev')).toBeNull();
  });

  it('says when only the most recent results are shown', async () => {
    api.listSchemaDriftFindings.mockResolvedValue({ ...page([finding]), total_elements: 250 });
    renderPage();
    expect((await screen.findByTestId('drift-truncated')).textContent).toContain('100 most recent');
  });

  it('re-reads the findings once a running scan finishes', async () => {
    const running = scan('e-prod', { finished_at: null, started_at: new Date().toISOString() });
    api.listSchemaDriftScans.mockResolvedValueOnce(page([running])).mockResolvedValue(
      page([{ ...running, finished_at: new Date().toISOString(), findings_count: 1 }]),
    );
    api.listSchemaDriftFindings.mockResolvedValueOnce(page([])).mockResolvedValue(page([finding]));
    renderPage();
    expect((await screen.findByTestId('drift-environment-prod')).textContent).toContain('Scan in progress');
    fireEvent.mouseDown(screen.getByLabelText('Status'));
    fireEvent.click(await screen.findByTitle('All statuses'));
    expect(await screen.findByText('public.orders.email', {}, { timeout: 8000 })).toBeTruthy();
  });

  it('keeps findings of an environment that left the ladder visible', async () => {
    api.listSchemaDriftFindings.mockResolvedValue(page([{ ...finding, id: 'f-2', environment_id: 'e-gone' }]));
    renderPage();
    expect(await screen.findByText('Deleted environment e-gone')).toBeTruthy();
  });
});
