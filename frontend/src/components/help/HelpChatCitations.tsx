import { useTranslation } from 'react-i18next';
import type { HelpChatCitation } from '@/types/api';

/**
 * The documentation sections an answer cited.
 *
 * This component is the **only** place the help panel renders a link. Each `url` was resolved
 * server-side from the chunk the model was actually given, never parsed out of the answer text
 * (epic #899 decision 6) — which is what keeps a jailbroken model from turning the help panel into
 * a phishing surface.
 */
export function HelpChatCitations({ citations }: { citations: HelpChatCitation[] }) {
  const { t } = useTranslation();
  if (citations.length === 0) return null;
  return (
    <div className="af-help-citations" aria-label={t('help_chat.citations_label')}>
      {citations.map((citation) => (
        <a
          key={`${citation.index}-${citation.chunk_id}`}
          className="af-help-citation"
          href={citation.url}
          target="_blank"
          rel="noopener noreferrer"
          title={citation.section ? `${citation.section} — ${citation.title}` : citation.title}
        >
          <span className="af-help-citation-index">[{citation.index}]</span>
          <span className="af-help-citation-title">{citation.title}</span>
        </a>
      ))}
    </div>
  );
}

export default HelpChatCitations;
