import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from 'antd';
import type { ReactNode } from 'react';
import '@/i18n';
import type {
  AttestationCampaign,
  AttestationCampaignPage,
  DatasourcePage,
} from '@/types/api';

const { listCampaignsMock, createCampaignMock, listDatasourcesMock } = vi.hoisted(() => ({
  listCampaignsMock: vi.fn(),
  createCampaignMock: vi.fn(),
  listDatasourcesMock: vi.fn(),
}));

vi.mock('@/api/attestation', async () => {
  const actual = await vi.importActual<typeof import('@/api/attestation')>('@/api/attestation');
  return {
    ...actual,
    listCampaigns: listCampaignsMock,
    createCampaign: createCampaignMock,
  };
});

vi.mock('@/api/datasources', async () => {
  const actual = await vi.importActual<typeof import('@/api/datasources')>('@/api/datasources');
  return {
    ...actual,
    listDatasources: listDatasourcesMock,
  };
});

const { default: CampaignListPage } = await import('./CampaignListPage');

function campaign(overrides: Partial<AttestationCampaign> = {}): AttestationCampaign {
  return {
    id: 'camp-1',
    name: 'Q3 Access Review',
    description: 'Quarterly recertification',
    scope: 'ORGANIZATION',
    datasource_id: null,
    datasource_name: null,
    status: 'SCHEDULED',
    pending_default: 'KEEP',
    scheduled_open_at: '2026-07-01T00:00:00Z',
    due_at: '2026-07-15T00:00:00Z',
    opened_at: null,
    closed_at: null,
    total_items: 12,
    pending_items: 12,
    certified_items: 0,
    revoked_items: 0,
    created_by: 'admin-1',
    created_at: '2026-06-20T00:00:00Z',
    ...overrides,
  };
}

function pageOf(content: AttestationCampaign[]): AttestationCampaignPage {
  return {
    content,
    page: 0,
    size: 20,
    total_elements: content.length,
    total_pages: content.length === 0 ? 0 : 1,
  };
}

function datasourcesPage(): DatasourcePage {
  return {
    content: [
      {
        id: 'ds-1',
        name: 'Prod Postgres',
        db_type: 'POSTGRESQL',
        host: 'db',
        port: 5432,
        database_name: 'app',
        username: 'app',
        ssl_mode: 'DISABLE',
        active: true,
        ai_analysis_enabled: false,
        ai_config_id: null,
        text_to_sql_enabled: false,
        custom_driver_id: null,
        jdbc_url_override: null,
        review_plan_id: null,
        created_at: '2026-06-01T00:00:00Z',
        updated_at: '2026-06-01T00:00:00Z',
      } as unknown as DatasourcePage['content'][number],
    ],
    page: 0,
    size: 100,
    total_elements: 1,
    total_pages: 1,
    last: true,
  };
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

describe('CampaignListPage', () => {
  beforeEach(() => {
    listCampaignsMock.mockReset();
    createCampaignMock.mockReset();
    listDatasourcesMock.mockReset();
    listDatasourcesMock.mockResolvedValue(datasourcesPage());
  });

  it('renders the page title and a campaign row', async () => {
    listCampaignsMock.mockResolvedValue(pageOf([campaign()]));

    render(wrap(<CampaignListPage />));

    expect(screen.getByText('Attestation campaigns')).toBeInTheDocument();
    await waitFor(() => {
      expect(screen.getByText('Q3 Access Review')).toBeInTheDocument();
    });
  });

  it('shows an empty state when there are no campaigns', async () => {
    listCampaignsMock.mockResolvedValue(pageOf([]));

    render(wrap(<CampaignListPage />));

    await waitFor(() => {
      expect(screen.getByText('No attestation campaigns')).toBeInTheDocument();
    });
  });

  it('shows a validation error when the name is blank on submit', async () => {
    listCampaignsMock.mockResolvedValue(pageOf([]));

    render(wrap(<CampaignListPage />));

    await waitFor(() => expect(listCampaignsMock).toHaveBeenCalled());

    fireEvent.click(screen.getByRole('button', { name: /Create campaign/i }));

    const dialog = await screen.findByRole('dialog');
    // Submit with the name left blank.
    fireEvent.click(within(dialog).getByRole('button', { name: /Create campaign/i }));

    await waitFor(() => {
      expect(screen.getByText('Name is required')).toBeInTheDocument();
    });
    expect(createCampaignMock).not.toHaveBeenCalled();
  });

  it('requires a datasource when the scope is DATASOURCE', async () => {
    listCampaignsMock.mockResolvedValue(pageOf([]));

    render(wrap(<CampaignListPage />));

    await waitFor(() => expect(listCampaignsMock).toHaveBeenCalled());

    fireEvent.click(screen.getByRole('button', { name: /Create campaign/i }));
    const dialog = await screen.findByRole('dialog');

    // Fill a valid name so only the datasource rule blocks submission.
    fireEvent.change(within(dialog).getByLabelText('Name'), {
      target: { value: 'Datasource campaign' },
    });

    // Switch scope to Datasource — the datasource field appears.
    const scopeSelect = within(dialog).getByLabelText('Scope');
    fireEvent.mouseDown(scopeSelect);
    await waitFor(() => {
      const opts = document.querySelectorAll('.ant-select-item-option-content');
      expect([...opts].some((o) => o.textContent === 'Datasource')).toBe(true);
    });
    const datasourceOption = [...document.querySelectorAll('.ant-select-item-option-content')].find(
      (o) => o.textContent === 'Datasource',
    );
    fireEvent.click(datasourceOption!);

    // The datasource Form.Item appears only after the scope switches.
    await screen.findByLabelText('Datasource');

    fireEvent.click(within(dialog).getByRole('button', { name: /Create campaign/i }));

    await waitFor(() => {
      expect(
        screen.getByText('A datasource is required for datasource-scoped campaigns'),
      ).toBeInTheDocument();
    });
    expect(createCampaignMock).not.toHaveBeenCalled();
  });
});
