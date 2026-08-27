import { describe, expect, it } from 'vitest';
import { apiBaseUrl } from '@/api/client';
import { fireEvent, render, screen } from '@testing-library/react';
import '@/i18n';
import type { DeploymentPipeline } from '@/types/api';
import { CiSnippetPanel } from './CiSnippetPanel';

const pipeline: DeploymentPipeline = {
  id: 'pipe-42',
  name: 'Prod Deploy',
  provider: 'GITHUB_ACTIONS',
  repository_url: 'https://github.com/acme/shop',
  project_ref: null,
  review_plan_id: null,
  ai_analysis_enabled: true,
  ai_config_id: null,
  active: true,
  created_at: '2026-05-01T00:00:00Z',
  updated_at: null,
};

const snippetText = () => screen.getByTestId('ci-snippet').textContent ?? '';

describe('CiSnippetPanel', () => {
  it('renders the GitHub Actions snippet by default with the pipeline id and API base URL', () => {
    render(<CiSnippetPanel pipeline={pipeline} />);

    const text = snippetText();
    expect(text).toContain('bablsoft/accessflow/.github/actions/deployment-gate@main');
    expect(text).toContain('pipeline-id: pipe-42');
    // The API base URL, not the SPA origin — CI posts to the backend.
    expect(text).toContain(`accessflow-url: ${apiBaseUrl().replace(/\/+$/, '')}`);
  });

  it('switches to the GitLab CI snippet', () => {
    render(<CiSnippetPanel pipeline={pipeline} />);

    fireEvent.click(screen.getByText('GitLab CI'));

    const text = snippetText();
    expect(text).toContain('.accessflow_deployment_gate');
    expect(text).toContain('AF_PIPELINE_ID: "pipe-42"');
    expect(text).not.toContain('deployment-gate@main');
  });

  it('switches to the Azure Pipelines snippet', () => {
    render(<CiSnippetPanel pipeline={pipeline} />);

    fireEvent.click(screen.getByText('Azure Pipelines'));

    const text = snippetText();
    expect(text).toContain('ci-templates/azure/accessflow-deployment.yml@accessflow');
    expect(text).toContain('pipelineId: "pipe-42"');
  });

  it('switches to the curl snippet targeting the deployment-requests endpoint', () => {
    render(<CiSnippetPanel pipeline={pipeline} />);

    fireEvent.click(screen.getByText('curl'));

    const text = snippetText();
    expect(text).toContain(`${apiBaseUrl().replace(/\/+$/, '')}/api/v1/deployment-requests`);
    expect(text).toContain('"pipeline_id": "pipe-42"');
    expect(text).toContain('/api/v1/deployment-gate?request_id=');
  });
});
