import { useState } from 'react';
import { App, Button, Empty, Input, Skeleton } from 'antd';
import { CommentOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import {
  commentKeys,
  createComment,
  listComments,
  reopenComment,
  replyToComment,
  resolveComment,
} from '@/api/comments';
import { showApiError } from '@/utils/showApiError';
import { collaborationErrorMessage } from '@/utils/apiErrors';
import { CommentThread } from './CommentThread';

interface CommentsPanelProps {
  queryId: string;
  /** Current editor selection (1-based line range) to anchor a new comment to. */
  selection: { start: number; end: number } | null;
  /** Snapshot of the anchored SQL text, for resilience after the buffer is resubmitted. */
  anchorSnapshot?: string | null;
}

/** Inline comment threads for a query in review: list, add, reply, resolve, reopen. */
export function CommentsPanel({ queryId, selection, anchorSnapshot }: CommentsPanelProps) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [body, setBody] = useState('');

  const { data: threads, isLoading } = useQuery({
    queryKey: commentKeys.all(queryId),
    queryFn: () => listComments(queryId),
  });

  const invalidate = () =>
    queryClient.invalidateQueries({ queryKey: commentKeys.all(queryId) });

  const createMutation = useMutation({
    mutationFn: () =>
      createComment(queryId, {
        anchor_start_line: selection?.start ?? 1,
        anchor_end_line: selection?.end ?? selection?.start ?? 1,
        anchor_snapshot: anchorSnapshot ?? null,
        body: body.trim(),
      }),
    onSuccess: () => {
      setBody('');
      void invalidate();
    },
    onError: (err) => showApiError(message, err, collaborationErrorMessage),
  });

  const replyMutation = useMutation({
    mutationFn: (vars: { commentId: string; body: string }) =>
      replyToComment(queryId, vars.commentId, vars.body),
    onSuccess: () => invalidate(),
    onError: (err) => showApiError(message, err, collaborationErrorMessage),
  });

  const resolveMutation = useMutation({
    mutationFn: (commentId: string) => resolveComment(queryId, commentId),
    onSuccess: () => invalidate(),
    onError: (err) => showApiError(message, err, collaborationErrorMessage),
  });

  const reopenMutation = useMutation({
    mutationFn: (commentId: string) => reopenComment(queryId, commentId),
    onSuccess: () => invalidate(),
    onError: (err) => showApiError(message, err, collaborationErrorMessage),
  });

  const busy =
    replyMutation.isPending || resolveMutation.isPending || reopenMutation.isPending;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
      <div style={{ fontWeight: 600, fontSize: 13, display: 'flex', gap: 8, alignItems: 'center' }}>
        <CommentOutlined />
        {t('collab.comments_title')}
      </div>

      <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
        <span className="muted" style={{ fontSize: 11 }}>
          {selection
            ? t('collab.comment_anchor', { start: selection.start, end: selection.end })
            : t('collab.select_lines_hint')}
        </span>
        <Input.TextArea
          rows={2}
          placeholder={t('collab.add_comment_placeholder')}
          value={body}
          onChange={(e) => setBody(e.target.value)}
          aria-label={t('collab.add_comment_placeholder')}
        />
        <div>
          <Button
            type="primary"
            size="small"
            loading={createMutation.isPending}
            disabled={!body.trim()}
            onClick={() => createMutation.mutate()}
          >
            {t('collab.add_comment')}
          </Button>
        </div>
      </div>

      {isLoading ? (
        <Skeleton active paragraph={{ rows: 3 }} />
      ) : threads && threads.length > 0 ? (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          {threads.map((thread) => (
            <CommentThread
              key={thread.root.id}
              thread={thread}
              busy={busy}
              onReply={(replyBody) =>
                replyMutation.mutate({ commentId: thread.root.id, body: replyBody })
              }
              onResolve={() => resolveMutation.mutate(thread.root.id)}
              onReopen={() => reopenMutation.mutate(thread.root.id)}
            />
          ))}
        </div>
      ) : (
        <Empty
          image={Empty.PRESENTED_IMAGE_SIMPLE}
          description={t('collab.no_comments')}
        />
      )}
    </div>
  );
}
