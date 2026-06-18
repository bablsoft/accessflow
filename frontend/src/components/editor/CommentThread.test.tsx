import { describe, expect, it, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { CommentThread } from './CommentThread';
import type { QueryComment, QueryCommentThread } from '@/types/api';

function comment(overrides: Partial<QueryComment> = {}): QueryComment {
  return {
    id: 'c-1',
    query_request_id: 'q-1',
    parent_comment_id: null,
    author: { id: 'u-1', display_name: 'Ann', email: 'ann@example.com' },
    anchor_start_line: 2,
    anchor_end_line: 4,
    anchor_snapshot: 'SELECT 1',
    body: 'needs an index',
    status: 'OPEN',
    resolved_by: null,
    resolved_at: null,
    created_at: new Date().toISOString(),
    updated_at: new Date().toISOString(),
    ...overrides,
  };
}

function thread(rootOverrides: Partial<QueryComment> = {},
  replies: QueryComment[] = []): QueryCommentThread {
  return { root: comment(rootOverrides), replies };
}

describe('CommentThread', () => {
  it('renders the root body, anchor range, and replies', () => {
    render(
      <CommentThread
        thread={thread({}, [comment({ id: 'r-1', parent_comment_id: 'c-1', body: 'agreed' })])}
        onReply={vi.fn()}
        onResolve={vi.fn()}
        onReopen={vi.fn()}
      />,
    );
    expect(screen.getByText('needs an index')).toBeInTheDocument();
    expect(screen.getByText('agreed')).toBeInTheDocument();
    expect(screen.getByText('Lines 2–4')).toBeInTheDocument();
  });

  it('submits a reply', () => {
    const onReply = vi.fn();
    render(
      <CommentThread thread={thread()} onReply={onReply} onResolve={vi.fn()} onReopen={vi.fn()} />,
    );
    fireEvent.change(screen.getByLabelText('Reply…'), { target: { value: 'me too' } });
    fireEvent.click(screen.getByRole('button', { name: 'Reply' }));
    expect(onReply).toHaveBeenCalledWith('me too');
  });

  it('resolves an open thread', () => {
    const onResolve = vi.fn();
    render(
      <CommentThread thread={thread()} onReply={vi.fn()} onResolve={onResolve} onReopen={vi.fn()} />,
    );
    fireEvent.click(screen.getByRole('button', { name: /Resolve/ }));
    expect(onResolve).toHaveBeenCalled();
  });

  it('reopens a resolved thread', () => {
    const onReopen = vi.fn();
    render(
      <CommentThread
        thread={thread({ status: 'RESOLVED' })}
        onReply={vi.fn()}
        onResolve={vi.fn()}
        onReopen={onReopen}
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: /Reopen/ }));
    expect(onReopen).toHaveBeenCalled();
  });
});
