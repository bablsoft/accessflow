import { describe, expect, it, vi, beforeEach } from 'vitest';

const { get, post, put, del } = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  del: vi.fn(),
}));

vi.mock('./client', () => ({
  apiClient: { get, post, put, delete: del },
}));

import * as api from './deploymentPipelines';
import { deploymentPipelineKeys } from './deploymentPipelines';

const pipelineFixture = {
  id: 'p-1',
  name: 'payments-api',
  provider: 'GITHUB_ACTIONS',
  repository_url: 'https://github.com/acme/payments-api',
  project_ref: null,
  review_plan_id: null,
  ai_analysis_enabled: true,
  ai_config_id: null,
  active: true,
  created_at: '2026-08-20T10:15:00Z',
  updated_at: null,
};

const environmentFixture = {
  id: 'env-1',
  pipeline_id: 'p-1',
  name: 'production',
  sort_order: 0,
  require_review: true,
  required_approvals: 2,
  review_plan_id: null,
  allow_break_glass: false,
  created_at: '2026-08-20T10:15:00Z',
};

const permissionFixture = {
  id: 'perm-1',
  pipeline_id: 'p-1',
  user_id: 'u-1',
  user_email: 'ci@example.com',
  user_display_name: 'CI Bot',
  can_trigger: true,
  can_break_glass: false,
  expires_at: null,
  created_at: '2026-08-20T10:15:00Z',
};

describe('api/deploymentPipelines', () => {
  beforeEach(() => {
    get.mockReset();
    post.mockReset();
    put.mockReset();
    del.mockReset();
  });

  it('builds hierarchical query keys', () => {
    expect(deploymentPipelineKeys.list({ page: 1 })).toEqual([
      'deployment-pipelines',
      'list',
      { page: 1 },
    ]);
    expect(deploymentPipelineKeys.detail('p-1')).toEqual(['deployment-pipelines', 'detail', 'p-1']);
    expect(deploymentPipelineKeys.environments('p-1')).toEqual([
      'deployment-pipelines',
      'detail',
      'p-1',
      'environments',
    ]);
    expect(deploymentPipelineKeys.permissions('p-1')).toEqual([
      'deployment-pipelines',
      'detail',
      'p-1',
      'permissions',
    ]);
    expect(deploymentPipelineKeys.groupPermissions('p-1')).toEqual([
      'deployment-pipelines',
      'detail',
      'p-1',
      'permissions',
      'groups',
    ]);
  });

  it('lists pipelines with pagination params', async () => {
    get.mockResolvedValue({ data: { content: [pipelineFixture], total_elements: 1 } });
    const page = await api.listDeploymentPipelines({ page: 2, size: 10 });
    expect(get).toHaveBeenCalledWith('/api/v1/deployment-pipelines', {
      params: { page: 2, size: 10 },
    });
    expect(page.content).toHaveLength(1);
  });

  it('omits pagination params when not provided', async () => {
    get.mockResolvedValue({ data: { content: [] } });
    await api.listDeploymentPipelines();
    expect(get).toHaveBeenCalledWith('/api/v1/deployment-pipelines', { params: {} });
  });

  it('gets a pipeline', async () => {
    get.mockResolvedValue({ data: pipelineFixture });
    const p = await api.getDeploymentPipeline('p-1');
    expect(get).toHaveBeenCalledWith('/api/v1/deployment-pipelines/p-1');
    expect(p.id).toBe('p-1');
  });

  it('creates a pipeline', async () => {
    post.mockResolvedValue({ data: pipelineFixture });
    await api.createDeploymentPipeline({ name: 'payments-api', provider: 'GITHUB_ACTIONS' });
    expect(post).toHaveBeenCalledWith('/api/v1/deployment-pipelines', {
      name: 'payments-api',
      provider: 'GITHUB_ACTIONS',
    });
  });

  it('updates a pipeline', async () => {
    put.mockResolvedValue({ data: pipelineFixture });
    await api.updateDeploymentPipeline('p-1', { name: 'renamed', clear_review_plan: true });
    expect(put).toHaveBeenCalledWith('/api/v1/deployment-pipelines/p-1', {
      name: 'renamed',
      clear_review_plan: true,
    });
  });

  it('deletes a pipeline', async () => {
    del.mockResolvedValue({ data: undefined });
    await api.deleteDeploymentPipeline('p-1');
    expect(del).toHaveBeenCalledWith('/api/v1/deployment-pipelines/p-1');
  });

  it('lists, creates, updates and deletes environments', async () => {
    get.mockResolvedValue({ data: [environmentFixture] });
    const envs = await api.listDeploymentEnvironments('p-1');
    expect(get).toHaveBeenCalledWith('/api/v1/deployment-pipelines/p-1/environments');
    expect(envs).toHaveLength(1);

    post.mockResolvedValue({ data: environmentFixture });
    await api.createDeploymentEnvironment('p-1', { name: 'production' });
    expect(post).toHaveBeenCalledWith('/api/v1/deployment-pipelines/p-1/environments', {
      name: 'production',
    });

    put.mockResolvedValue({ data: environmentFixture });
    await api.updateDeploymentEnvironment('p-1', 'env-1', { required_approvals: 3 });
    expect(put).toHaveBeenCalledWith('/api/v1/deployment-pipelines/p-1/environments/env-1', {
      required_approvals: 3,
    });

    del.mockResolvedValue({ data: undefined });
    await api.deleteDeploymentEnvironment('p-1', 'env-1');
    expect(del).toHaveBeenCalledWith('/api/v1/deployment-pipelines/p-1/environments/env-1');
  });

  it('lists, grants, updates and revokes user permissions', async () => {
    get.mockResolvedValue({ data: [permissionFixture] });
    const perms = await api.listDeploymentPermissions('p-1');
    expect(get).toHaveBeenCalledWith('/api/v1/deployment-pipelines/p-1/permissions');
    expect(perms[0]?.can_trigger).toBe(true);

    post.mockResolvedValue({ data: permissionFixture });
    await api.grantDeploymentPermission('p-1', { user_id: 'u-1', can_trigger: true });
    expect(post).toHaveBeenCalledWith('/api/v1/deployment-pipelines/p-1/permissions', {
      user_id: 'u-1',
      can_trigger: true,
    });

    put.mockResolvedValue({ data: permissionFixture });
    await api.updateDeploymentPermission('p-1', 'perm-1', { can_break_glass: true });
    expect(put).toHaveBeenCalledWith('/api/v1/deployment-pipelines/p-1/permissions/perm-1', {
      can_break_glass: true,
    });

    del.mockResolvedValue({ data: undefined });
    await api.revokeDeploymentPermission('p-1', 'perm-1');
    expect(del).toHaveBeenCalledWith('/api/v1/deployment-pipelines/p-1/permissions/perm-1');
  });

  it('lists, grants, updates and revokes group permissions', async () => {
    get.mockResolvedValue({ data: [] });
    await api.listDeploymentGroupPermissions('p-1');
    expect(get).toHaveBeenCalledWith('/api/v1/deployment-pipelines/p-1/permissions/groups');

    post.mockResolvedValue({ data: {} });
    await api.grantDeploymentGroupPermission('p-1', { group_id: 'g-1', can_trigger: true });
    expect(post).toHaveBeenCalledWith('/api/v1/deployment-pipelines/p-1/permissions/groups', {
      group_id: 'g-1',
      can_trigger: true,
    });

    put.mockResolvedValue({ data: {} });
    await api.updateDeploymentGroupPermission('p-1', 'perm-2', { can_trigger: false });
    expect(put).toHaveBeenCalledWith('/api/v1/deployment-pipelines/p-1/permissions/groups/perm-2', {
      can_trigger: false,
    });

    del.mockResolvedValue({ data: undefined });
    await api.revokeDeploymentGroupPermission('p-1', 'perm-2');
    expect(del).toHaveBeenCalledWith('/api/v1/deployment-pipelines/p-1/permissions/groups/perm-2');
  });
});
