import { describe, expect, it, vi, beforeEach } from 'vitest';

const { get, post } = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
}));

vi.mock('./client', () => ({
  apiClient: { get, post },
}));

import * as anomaliesApi from './anomalies';
import { anomalyKeys } from './anomalies';
import type { BehaviorAnomaly } from '@/types/api';

const anomalyFixture: BehaviorAnomaly = {
  id: 'an-1',
  user_id: 'u-1',
  user_display_name: 'Alice',
  user_email: 'alice@example.com',
  datasource_id: 'ds-1',
  datasource_name: 'Prod',
  feature: 'rows_read_per_hour',
  score: 4.2,
  observed_value: 9000,
  baseline_mean: 1200,
  baseline_stddev: 300,
  detail: { window: '1h' },
  ai_summary: 'Read volume far above baseline.',
  status: 'OPEN',
  detected_at: '2026-06-20T10:00:00Z',
  acknowledged_by: null,
  acknowledged_at: null,
  window_start: '2026-06-20T09:00:00Z',
  window_end: '2026-06-20T10:00:00Z',
};

describe('api/anomalies', () => {
  beforeEach(() => {
    get.mockReset();
    post.mockReset();
  });

  describe('anomalyKeys', () => {
    it('builds hierarchical query keys', () => {
      expect(anomalyKeys.all).toEqual(['anomalies']);
      expect(anomalyKeys.lists()).toEqual(['anomalies', 'list']);
      expect(anomalyKeys.list({ status: 'OPEN' })).toEqual([
        'anomalies',
        'list',
        { status: 'OPEN' },
      ]);
      expect(anomalyKeys.details()).toEqual(['anomalies', 'detail']);
      expect(anomalyKeys.detail('an-1')).toEqual(['anomalies', 'detail', 'an-1']);
      expect(anomalyKeys.badge('ds-1')).toEqual(['anomalies', 'badge', 'ds-1']);
      expect(anomalyKeys.badge()).toEqual(['anomalies', 'badge', 'all']);
      expect(anomalyKeys.mine({ status: 'OPEN' })).toEqual([
        'anomalies',
        'mine',
        { status: 'OPEN' },
      ]);
    });
  });

  describe('listMyAnomalies', () => {
    it('GETs the self-scoped endpoint with status + paging params', async () => {
      get.mockResolvedValueOnce({
        data: { content: [anomalyFixture], page: 0, size: 10, total_elements: 1, total_pages: 1 },
      });
      const result = await anomaliesApi.listMyAnomalies({ page: 0, size: 10, status: 'OPEN' });
      expect(get).toHaveBeenCalledWith('/api/v1/anomalies/mine', {
        params: { page: 0, size: 10, status: 'OPEN' },
      });
      expect(result.content).toHaveLength(1);
    });

    it('GETs with no params by default', async () => {
      get.mockResolvedValueOnce({
        data: { content: [], page: 0, size: 20, total_elements: 0, total_pages: 0 },
      });
      await anomaliesApi.listMyAnomalies();
      expect(get).toHaveBeenCalledWith('/api/v1/anomalies/mine', { params: {} });
    });
  });

  describe('acknowledgeMyAnomaly / dismissMyAnomaly', () => {
    it('POSTs to the self-scoped acknowledge endpoint', async () => {
      post.mockResolvedValueOnce({ data: { ...anomalyFixture, status: 'ACKNOWLEDGED' } });
      const result = await anomaliesApi.acknowledgeMyAnomaly('an-1');
      expect(post).toHaveBeenCalledWith('/api/v1/anomalies/mine/an-1/acknowledge');
      expect(result.status).toBe('ACKNOWLEDGED');
    });

    it('POSTs to the self-scoped dismiss endpoint', async () => {
      post.mockResolvedValueOnce({ data: { ...anomalyFixture, status: 'DISMISSED' } });
      const result = await anomaliesApi.dismissMyAnomaly('an-1');
      expect(post).toHaveBeenCalledWith('/api/v1/anomalies/mine/an-1/dismiss');
      expect(result.status).toBe('DISMISSED');
    });
  });

  describe('listAnomalies', () => {
    it('GETs the admin endpoint with no params by default', async () => {
      get.mockResolvedValueOnce({
        data: { content: [], page: 0, size: 20, total_elements: 0, total_pages: 0 },
      });
      await anomaliesApi.listAnomalies();
      expect(get).toHaveBeenCalledWith('/api/v1/admin/anomalies', { params: {} });
    });

    it('maps every filter onto camelCase query params', async () => {
      get.mockResolvedValueOnce({
        data: { content: [anomalyFixture], page: 1, size: 10, total_elements: 11, total_pages: 2 },
      });
      const result = await anomaliesApi.listAnomalies({
        page: 1,
        size: 10,
        status: 'ACKNOWLEDGED',
        user_id: 'u-1',
        datasource_id: 'ds-1',
        feature: 'rows_read_per_hour',
        from: '2026-06-01T00:00:00Z',
        to: '2026-06-30T00:00:00Z',
      });
      expect(get).toHaveBeenCalledWith('/api/v1/admin/anomalies', {
        params: {
          page: 1,
          size: 10,
          status: 'ACKNOWLEDGED',
          userId: 'u-1',
          datasourceId: 'ds-1',
          feature: 'rows_read_per_hour',
          from: '2026-06-01T00:00:00Z',
          to: '2026-06-30T00:00:00Z',
        },
      });
      expect(result.content).toHaveLength(1);
    });

    it('omits blank string filters', async () => {
      get.mockResolvedValueOnce({
        data: { content: [], page: 0, size: 20, total_elements: 0, total_pages: 0 },
      });
      await anomaliesApi.listAnomalies({ user_id: '', feature: '' });
      expect(get).toHaveBeenCalledWith('/api/v1/admin/anomalies', { params: {} });
    });
  });

  describe('getAnomaly', () => {
    it('GETs a single anomaly by id', async () => {
      get.mockResolvedValueOnce({ data: anomalyFixture });
      const result = await anomaliesApi.getAnomaly('an-1');
      expect(get).toHaveBeenCalledWith('/api/v1/admin/anomalies/an-1');
      expect(result.id).toBe('an-1');
    });
  });

  describe('acknowledgeAnomaly', () => {
    it('POSTs to the acknowledge endpoint', async () => {
      get.mockReset();
      post.mockResolvedValueOnce({ data: { ...anomalyFixture, status: 'ACKNOWLEDGED' } });
      const result = await anomaliesApi.acknowledgeAnomaly('an-1');
      expect(post).toHaveBeenCalledWith('/api/v1/admin/anomalies/an-1/acknowledge');
      expect(result.status).toBe('ACKNOWLEDGED');
    });
  });

  describe('dismissAnomaly', () => {
    it('POSTs to the dismiss endpoint', async () => {
      post.mockResolvedValueOnce({ data: { ...anomalyFixture, status: 'DISMISSED' } });
      const result = await anomaliesApi.dismissAnomaly('an-1');
      expect(post).toHaveBeenCalledWith('/api/v1/admin/anomalies/an-1/dismiss');
      expect(result.status).toBe('DISMISSED');
    });
  });

  describe('fetchAnomalyBadge', () => {
    it('GETs the badge with no datasource scope by default', async () => {
      get.mockResolvedValueOnce({ data: { openCount: 3, maxScore: 5.1 } });
      const result = await anomaliesApi.fetchAnomalyBadge();
      expect(get).toHaveBeenCalledWith('/api/v1/anomalies/badge', { params: {} });
      expect(result).toEqual({ openCount: 3, maxScore: 5.1 });
    });

    it('passes datasourceId when scoping the badge', async () => {
      get.mockResolvedValueOnce({ data: { openCount: 1, maxScore: 2.0 } });
      await anomaliesApi.fetchAnomalyBadge('ds-9');
      expect(get).toHaveBeenCalledWith('/api/v1/anomalies/badge', {
        params: { datasourceId: 'ds-9' },
      });
    });
  });
});
