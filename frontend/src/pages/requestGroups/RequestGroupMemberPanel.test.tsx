import { describe, expect, it } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import '@/i18n';
import { RequestGroupMemberPanel } from './RequestGroupMemberPanel';
import type { AiAnalysisDetail, RequestGroupItem } from '@/types/api';

function item(overrides: Partial<RequestGroupItem> = {}): RequestGroupItem {
  return {
    id: 'i-1',
    sequence_order: 0,
    target_kind: 'QUERY',
    datasource_id: 'ds-1',
    datasource_name: 'prod-db',
    sql_text: 'SELECT * FROM users',
    query_type: 'SELECT',
    transactional: false,
    api_connector_id: null,
    api_connector_name: null,
    operation_id: null,
    verb: null,
    request_path: null,
    request_headers: null,
    query_params: null,
    body_type: null,
    request_content_type: null,
    request_body: null,
    form_fields: null,
    binary_filename: null,
    ai_analysis_id: null,
    ai_risk_level: 'MEDIUM',
    ai_risk_score: 40,
    ai_analysis: null,
    status: 'PENDING',
    response_status_code: null,
    rows_affected: null,
    error_message: null,
    duration_ms: null,
    executed_at: null,
    ...overrides,
  };
}

function analysis(overrides: Partial<AiAnalysisDetail> = {}): AiAnalysisDetail {
  return {
    id: 'a-1',
    risk_level: 'MEDIUM',
    risk_score: 40,
    summary: 'Reads all user rows.',
    issues: [],
    optimizations: [],
    missing_indexes_detected: false,
    affects_row_estimate: null,
    ai_provider: 'OPENAI',
    ai_model: 'gpt-4o',
    prompt_tokens: 12,
    completion_tokens: 7,
    failed: false,
    error_message: null,
    ...overrides,
  };
}

describe('RequestGroupMemberPanel', () => {
  it('is collapsed by default and expands on toggle', async () => {
    render(<RequestGroupMemberPanel item={item()} />);

    const toggle = screen.getByTestId('group-step-0-toggle');
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByTestId('group-step-0-body')).not.toBeInTheDocument();

    fireEvent.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByTestId('group-step-0-body')).toBeInTheDocument();

    fireEvent.click(toggle);
    expect(screen.queryByTestId('group-step-0-body')).not.toBeInTheDocument();
  });

  it('renders the SQL block and datasource for QUERY members when expanded', async () => {
    render(<RequestGroupMemberPanel item={item()} />);
    fireEvent.click(screen.getByTestId('group-step-0-toggle'));

    const body = screen.getByTestId('group-step-0-body');
    expect(body).toHaveTextContent('prod-db');
    expect(body).toHaveTextContent('SELECT * FROM users');
  });

  it('renders verb, path and connector for API_CALL members when expanded', async () => {
    render(
      <RequestGroupMemberPanel
        item={item({
          target_kind: 'API_CALL',
          datasource_id: null,
          datasource_name: null,
          sql_text: null,
          query_type: null,
          api_connector_id: 'c-1',
          api_connector_name: 'billing-api',
          verb: 'POST',
          request_path: '/v1/invoices',
        })}
      />,
    );
    fireEvent.click(screen.getByTestId('group-step-0-toggle'));

    const body = screen.getByTestId('group-step-0-body');
    expect(body).toHaveTextContent('POST');
    expect(body).toHaveTextContent('/v1/invoices');
    expect(body).toHaveTextContent('billing-api');
  });

  it('renders the AI analysis card with summary, issues and optimizations', async () => {
    render(
      <RequestGroupMemberPanel
        item={item({
          ai_analysis_id: 'a-1',
          ai_analysis: analysis({
            issues: [
              {
                severity: 'HIGH',
                category: 'security',
                message: 'Unbounded read',
                suggestion: 'Add a LIMIT clause',
              },
            ],
            optimizations: [
              {
                type: 'INDEX',
                title: 'Add index on users.email',
                rationale: 'Speeds up the lookup',
                sql: 'CREATE INDEX idx_users_email ON users(email)',
              },
            ],
          }),
        })}
      />,
    );
    fireEvent.click(screen.getByTestId('group-step-0-toggle'));

    const ai = screen.getByTestId('group-step-0-ai');
    expect(ai).toHaveTextContent('Reads all user rows.');
    expect(ai).toHaveTextContent('Unbounded read');
    expect(ai).toHaveTextContent('Add index on users.email');
    expect(ai).toHaveTextContent('gpt-4o');
  });

  it('renders the failed state with the failure reason', async () => {
    render(
      <RequestGroupMemberPanel
        item={item({
          ai_analysis_id: 'a-1',
          ai_analysis: analysis({ failed: true, error_message: 'provider unavailable' }),
        })}
      />,
    );
    fireEvent.click(screen.getByTestId('group-step-0-toggle'));

    expect(screen.getByTestId('group-step-0-ai')).toHaveTextContent('provider unavailable');
  });

  it('renders a not-available note when the member has no analysis', async () => {
    render(<RequestGroupMemberPanel item={item()} />);
    fireEvent.click(screen.getByTestId('group-step-0-toggle'));

    expect(screen.getByTestId('group-step-0-ai')).toHaveTextContent(
      'AI analysis is not available for this step.',
    );
  });

  it('renders the error block when the member run failed', async () => {
    render(<RequestGroupMemberPanel item={item({ error_message: 'duplicate key value' })} />);
    fireEvent.click(screen.getByTestId('group-step-0-toggle'));

    expect(screen.getByTestId('group-step-0-body')).toHaveTextContent('duplicate key value');
  });
});
