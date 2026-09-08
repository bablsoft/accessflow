import { useEffect, useRef } from 'react';
import { Skeleton, Spin } from 'antd';
import { useTranslation } from 'react-i18next';
import { HelpChatCitations } from './HelpChatCitations';
import type { HelpChatMessage } from '@/types/api';

interface Props {
  messages: HelpChatMessage[];
  loading: boolean;
  answering: boolean;
  /** Shown before the first question, so an empty drawer explains itself. */
  emptyHint: string;
}

/**
 * The transcript.
 *
 * `content` is rendered as **plain text** — a text node inside a `<div>`, with line breaks
 * preserved by CSS. No markdown, no `dangerouslySetInnerHTML`, and deliberately no URL
 * auto-linking: a URL the model wrote is text, and the only anchors on this panel come from
 * {@link HelpChatCitations} (epic #899 decision 6).
 */
export function HelpChatMessageList({ messages, loading, answering, emptyHint }: Props) {
  const { t } = useTranslation();
  const endRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    endRef.current?.scrollIntoView({ block: 'end' });
  }, [messages.length, answering]);

  if (loading) {
    return (
      <div className="af-help-messages">
        <Skeleton active paragraph={{ rows: 4 }} />
      </div>
    );
  }

  return (
    <div className="af-help-messages" role="log" aria-label={t('help_chat.transcript_label')}>
      {messages.length === 0 && !answering ? (
        <div className="af-help-composer-hint">{emptyHint}</div>
      ) : null}
      {messages.map((message) => (
        <div
          key={message.id}
          className={`af-help-message af-help-message-${message.role === 'USER' ? 'user' : 'assistant'}`}
        >
          <div className="af-help-bubble">{message.content}</div>
          {message.role === 'ASSISTANT' ? (
            <HelpChatCitations citations={message.citations} />
          ) : null}
        </div>
      ))}
      {answering ? (
        <div className="af-help-message af-help-message-assistant af-help-message-pending">
          <div className="af-help-bubble">
            <Spin size="small" /> {t('help_chat.thinking')}
          </div>
        </div>
      ) : null}
      <div ref={endRef} />
    </div>
  );
}

export default HelpChatMessageList;
