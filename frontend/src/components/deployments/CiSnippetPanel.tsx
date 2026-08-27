import { useState } from 'react';
import { Segmented, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { apiBaseUrl } from '@/api/client';
import type { DeploymentPipeline } from '@/types/api';

type CiPlatform = 'github' | 'gitlab' | 'azure' | 'curl';

/* CI snippets are code, not copy — only the surrounding prose is translated. The templates
   mirror ci-templates/examples/* with this pipeline's id and the current origin pre-filled. */

const githubSnippet = (origin: string, pipelineId: string) => `- name: Wait for AccessFlow deployment approval
  id: gate
  uses: bablsoft/accessflow/.github/actions/deployment-gate@main
  with:
    accessflow-url: ${origin}
    api-key: \${{ secrets.ACCESSFLOW_API_KEY }}
    pipeline-id: ${pipelineId}
    version: \${{ inputs.version }}
    environment: production
    commit-sha: \${{ github.sha }}
    wait-timeout: 30m
    poll-interval: 15s

- name: Deploy
  run: ./deploy.sh "\${{ inputs.version }}"

- name: Report deployment outcome
  if: always()
  uses: bablsoft/accessflow/.github/actions/deployment-outcome@main
  with:
    accessflow-url: ${origin}
    api-key: \${{ secrets.ACCESSFLOW_API_KEY }}
    request-id: \${{ steps.gate.outputs.request-id }}
    job-status: \${{ job.status }}`;

const gitlabSnippet = (origin: string, pipelineId: string) => `# ACCESSFLOW_API_KEY must exist as a masked, protected CI/CD variable.
include:
  - remote: "https://raw.githubusercontent.com/bablsoft/accessflow/main/ci-templates/gitlab/accessflow-deployment.gitlab-ci.yml"

stages: [gate, deploy, report]

variables:
  ACCESSFLOW_ENDPOINT: "${origin}"
  AF_PIPELINE_ID: "${pipelineId}"

gate_production:
  stage: gate
  extends: .accessflow_deployment_gate
  variables:
    AF_VERSION: "$CI_COMMIT_SHORT_SHA"
    AF_ENVIRONMENT: "production"

deploy_production:
  stage: deploy
  needs: [gate_production]
  script:
    - ./deploy.sh "$CI_COMMIT_SHORT_SHA"

report_success:
  stage: report
  extends: .accessflow_deployment_outcome
  needs: [gate_production, deploy_production]
  variables:
    AF_OUTCOME: "SUCCEEDED"

report_failure:
  stage: report
  extends: .accessflow_deployment_outcome
  needs: [gate_production, deploy_production]
  when: on_failure
  variables:
    AF_OUTCOME: "FAILED"`;

const azureSnippet = (origin: string, pipelineId: string) => `# The 'accessflow' variable group holds the secret variable 'accessflow-api-key'.
variables:
  - group: accessflow

resources:
  repositories:
    - repository: accessflow
      type: github
      name: bablsoft/accessflow
      endpoint: github-connection

steps:
  - template: ci-templates/azure/accessflow-deployment.yml@accessflow
    parameters:
      accessflowUrl: "${origin}"
      pipelineId: "${pipelineId}"
      version: "$(Build.SourceBranchName)"
      environment: "production"
      waitTimeout: "30m"
      pollInterval: "15s"
      deploySteps:
        - script: ./deploy.sh "$(Build.SourceBranchName)"
          displayName: Deploy`;

const curlSnippet = (origin: string, pipelineId: string) => `request_id=$(curl -fsS -X POST \\
  -H "Authorization: ApiKey $ACCESSFLOW_API_KEY" \\
  -H "X-AccessFlow-CI: true" \\
  -H "Content-Type: application/json" \\
  -d '{
        "pipeline_id": "${pipelineId}",
        "environment": "production",
        "version": "'"$VERSION"'",
        "external_run_id": "'"$BUILD_ID"'",
        "commit_sha": "'"$COMMIT_SHA"'"
      }' \\
  "${origin}/api/v1/deployment-requests" | jq -r '.id')

# Poll until releasable, then confirm and report — full walkthrough:
# https://github.com/bablsoft/accessflow/blob/main/ci-templates/examples/generic-curl-deployment.md
curl -sS -H "Authorization: ApiKey $ACCESSFLOW_API_KEY" \\
  "${origin}/api/v1/deployment-gate?request_id=$request_id"`;

const SNIPPETS: Record<CiPlatform, (origin: string, pipelineId: string) => string> = {
  github: githubSnippet,
  gitlab: gitlabSnippet,
  azure: azureSnippet,
  curl: curlSnippet,
};

export function CiSnippetPanel({ pipeline }: { pipeline: DeploymentPipeline }) {
  const { t } = useTranslation();
  const [platform, setPlatform] = useState<CiPlatform>('github');
  // The API base URL, never window.location.origin — CI talks to the backend, and the SPA is
  // served from a different origin with no /api proxy, so an origin-based snippet would POST
  // to the SPA and get index.html back.
  const origin = apiBaseUrl().replace(/\/+$/, '');
  const snippet = SNIPPETS[platform](origin, pipeline.id);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 12, maxWidth: 860 }}>
      <div className="muted" style={{ fontSize: 12, lineHeight: 1.55 }}>
        {t('deploygov.settings.ciIntro')}
      </div>
      <Segmented<CiPlatform>
        value={platform}
        onChange={(v) => setPlatform(v)}
        options={[
          { value: 'github', label: t('deploygov.settings.ciGithub') },
          { value: 'gitlab', label: t('deploygov.settings.ciGitlab') },
          { value: 'azure', label: t('deploygov.settings.ciAzure') },
          { value: 'curl', label: t('deploygov.settings.ciCurl') },
        ]}
      />
      <Typography.Paragraph
        copyable={{ text: snippet }}
        style={{ margin: 0 }}
        data-testid="ci-snippet"
      >
        <pre
          style={{
            background: 'var(--bg-sunken)',
            border: '1px solid var(--border)',
            borderRadius: 'var(--radius-md)',
            padding: 12,
            margin: 0,
            maxHeight: 420,
            overflow: 'auto',
            fontSize: 12,
            whiteSpace: 'pre',
          }}
        >
          {snippet}
        </pre>
      </Typography.Paragraph>
    </div>
  );
}
