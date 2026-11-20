import { useState } from 'react';
import { Button, Input } from 'antd';
import { CheckOutlined, RollbackOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { StatusTag } from '@/components/layout';
import { commentStatusLabel } from '@/utils/enumLabels';
import { timeAgo } from '@/utils/dateFormat';
import { userDisplay } from '@/utils/userDisplay';
import type { QueryComment, QueryCommentThread } from '@/types/api';

interface CommentThreadProps {
  thread: QueryCommentThread;
  onReply: (body: string) => void;
  onResolve: () => void;
  onReopen: () => void;
  busy?: boolean;
}

function CommentBody({ comment }: { comment: QueryComment }) {
  return (
    <div className="u-text-sm u-leading-normal">
      <div className="u-flex u-gap-2 u-items-baseline">
        <strong>{userDisplay(comment.author.display_name, comment.author.email)}</strong>
        <span className="muted u-text-2xs">{timeAgo(comment.created_at)}</span>
      </div>
      <div style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{comment.body}</div>
    </div>
  );
}

/** A single inline comment thread anchored to a line range, with reply and resolve/reopen. */
export function CommentThread({ thread, onReply, onResolve, onReopen, busy }: CommentThreadProps) {
  const { t } = useTranslation();
  const [replyBody, setReplyBody] = useState('');
  const resolved = thread.root.status === 'RESOLVED';

  const submitReply = () => {
    const body = replyBody.trim();
    if (!body) return;
    onReply(body);
    setReplyBody('');
  };

  return (
    <div
      className="u-p-3" style={{ border: '1px solid var(--border)', borderRadius: 'var(--radius-sm)', background: 'var(--bg-elev)', opacity: resolved ? 0.7 : 1 }}
    >
      <div className="u-flex u-justify-between u-mb-2">
        <span className="mono muted u-text-2xs">
          {t('collab.comment_anchor', {
            start: thread.root.anchor_start_line,
            end: thread.root.anchor_end_line,
          })}
        </span>
        <StatusTag tone={resolved ? 'success' : 'info'}>
          {commentStatusLabel(t, thread.root.status)}
        </StatusTag>
      </div>

      <CommentBody comment={thread.root} />

      {thread.replies.length > 0 && (
        <div
          className="u-mt-2 u-flex u-col u-gap-2" style={{ paddingLeft: 12, borderLeft: '2px solid var(--border)' }}
        >
          {thread.replies.map((reply) => (
            <CommentBody key={reply.id} comment={reply} />
          ))}
        </div>
      )}

      <div className="u-flex u-gap-2" style={{ marginTop: 10 }}>
        <Input
          size="small"
          placeholder={t('collab.reply_placeholder')}
          value={replyBody}
          onChange={(e) => setReplyBody(e.target.value)}
          onPressEnter={submitReply}
          aria-label={t('collab.reply_placeholder')}
        />
        <Button size="small" onClick={submitReply} disabled={busy || !replyBody.trim()}>
          {t('collab.reply')}
        </Button>
        {resolved ? (
          <Button size="small" icon={<RollbackOutlined />} onClick={onReopen} disabled={busy}>
            {t('collab.reopen')}
          </Button>
        ) : (
          <Button
            size="small"
            type="primary"
            icon={<CheckOutlined />}
            onClick={onResolve}
            disabled={busy}
          >
            {t('collab.resolve')}
          </Button>
        )}
      </div>
    </div>
  );
}
