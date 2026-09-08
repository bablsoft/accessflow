import { useEffect, useRef } from 'react';
import { Skeleton, Spin } from 'antd';
import { useTranslation } from 'react-i18next';
import { HelpChatCitations } from './HelpChatCitations';
import { HelpChatMarkdown } from './HelpChatMarkdown';
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
 * An assistant answer is rendered through {@link HelpChatMarkdown}, a closed markdown subset that
 * has no anchor, image or raw-HTML case at all (AF-919). A question the user typed stays a plain
 * text node with its line breaks preserved by CSS. Either way there is no
 * `dangerouslySetInnerHTML` and no URL auto-linking: a URL the model wrote is text, and the only
 * anchors on this panel come from {@link HelpChatCitations} (epic #899 decision 6).
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
          {message.role === 'ASSISTANT' ? (
            <>
              <div className="af-help-bubble af-help-bubble-rich">
                <HelpChatMarkdown content={message.content} />
              </div>
              <HelpChatCitations citations={message.citations} />
            </>
          ) : (
            <div className="af-help-bubble">{message.content}</div>
          )}
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
