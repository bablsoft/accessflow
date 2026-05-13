import { describe, expect, it, vi, beforeEach } from 'vitest';

const { get, post } = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }));

vi.mock('./client', () => ({
  apiClient: { get, post },
}));

import * as invitationsApi from './invitations';

describe('api/invitations', () => {
  beforeEach(() => {
    get.mockReset();
    post.mockReset();
  });

  it('getInvitationPreview GETs the public endpoint', async () => {
    const preview = {
      email: 'alice@example.com',
      display_name: 'Alice',
      role: 'ANALYST' as const,
      organization_name: 'Acme',
      expires_at: '2026-05-20T00:00:00Z',
    };
    get.mockResolvedValueOnce({ data: preview });
    const result = await invitationsApi.getInvitationPreview('raw-token');
    expect(get).toHaveBeenCalledWith('/api/v1/auth/invitations/raw-token');
    expect(result.email).toBe('alice@example.com');
  });

  it('getInvitationPreview URL-encodes the token', async () => {
    get.mockResolvedValueOnce({ data: { email: 'x@y', display_name: null, role: 'ANALYST', organization_name: 'A', expires_at: '' } });
    await invitationsApi.getInvitationPreview('with/slash');
    expect(get).toHaveBeenCalledWith('/api/v1/auth/invitations/with%2Fslash');
  });

  it('acceptInvitation POSTs the password payload', async () => {
    post.mockResolvedValueOnce({});
    await invitationsApi.acceptInvitation('raw-token', {
      password: 'password1',
      display_name: 'Alice',
    });
    expect(post).toHaveBeenCalledWith('/api/v1/auth/invitations/raw-token/accept', {
      password: 'password1',
      display_name: 'Alice',
    });
  });
});
