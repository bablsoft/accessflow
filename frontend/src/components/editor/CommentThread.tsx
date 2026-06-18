import { useState } from 'react';
import { Button, Input, Tag } from 'antd';
import { CheckOutlined, RollbackOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
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
    <div style={{ fontSize: 13, lineHeight: 1.5 }}>
      <div style={{ display: 'flex', gap: 8, alignItems: 'baseline' }}>
        <strong>{userDisplay(comment.author.display_name, comment.author.email)}</strong>
        <span className="muted" style={{ fontSize: 11 }}>{timeAgo(comment.created_at)}</span>
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
      style={{
        border: '1px solid var(--border)',
        borderRadius: 'var(--radius-sm)',
        padding: 12,
        background: 'var(--bg-elev)',
        opacity: resolved ? 0.7 : 1,
      }}
    >
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 8 }}>
        <span className="mono muted" style={{ fontSize: 11 }}>
          {t('collab.comment_anchor', {
            start: thread.root.anchor_start_line,
            end: thread.root.anchor_end_line,
          })}
        </span>
        <Tag color={resolved ? 'green' : 'blue'}>
          {commentStatusLabel(t, thread.root.status)}
        </Tag>
      </div>

      <CommentBody comment={thread.root} />

      {thread.replies.length > 0 && (
        <div
          style={{
            marginTop: 8,
            paddingLeft: 12,
            borderLeft: '2px solid var(--border)',
            display: 'flex',
            flexDirection: 'column',
            gap: 8,
          }}
        >
          {thread.replies.map((reply) => (
            <CommentBody key={reply.id} comment={reply} />
          ))}
        </div>
      )}

      <div style={{ display: 'flex', gap: 8, marginTop: 10 }}>
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
