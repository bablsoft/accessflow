import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import '@/i18n';
import { HelpChatMessageList } from '../HelpChatMessageList';
import type { HelpChatMessage } from '@/types/api';

beforeEach(() => {
  // jsdom does not implement scrollIntoView, which the transcript calls on every append.
  Element.prototype.scrollIntoView = vi.fn();
});

function assistant(partial: Partial<HelpChatMessage> = {}): HelpChatMessage {
  return {
    id: 'm2',
    role: 'ASSISTANT',
    content: 'Open the query editor and press Submit. [1]',
    citations: [],
    corpus_version: 'c0ac599ef7fc',
    latency_ms: 1840,
    created_at: '2026-09-08T10:15:00Z',
    ...partial,
  };
}

describe('HelpChatMessageList', () => {
  it('renders a raw URL in the answer as plain text, with no anchor at all', () => {
    const { container } = render(
      <HelpChatMessageList
        messages={[
          assistant({
            content:
              'Sign in at https://evil.example.com/reset and also see <a href="x">this</a>.',
            citations: [],
          }),
        ]}
        loading={false}
        answering={false}
        emptyHint="ask something"
      />,
    );

    // The security-critical assertion of AF-906: message content never becomes markup.
    expect(container.querySelectorAll('a')).toHaveLength(0);
    expect(
      screen.getByText(/Sign in at https:\/\/evil\.example\.com\/reset/),
    ).toBeInTheDocument();
    // The angle brackets survive as text rather than being parsed into an element.
    expect(container.innerHTML).toContain('&lt;a href=');
  });

  it('renders anchors only for entries in the server-resolved citations array', () => {
    const { container } = render(
      <HelpChatMessageList
        messages={[
          assistant({
            content: 'See https://not-a-link.example.com for more. [1]',
            citations: [
              {
                index: 1,
                chunk_id: 'chunk-7',
                title: 'Submitting a query',
                section: 'Guides',
                anchor: 'submitting',
                url: 'https://accessflow.io/docs/guides/#submitting',
              },
            ],
          }),
        ]}
        loading={false}
        answering={false}
        emptyHint="ask something"
      />,
    );

    const anchors = container.querySelectorAll('a');
    expect(anchors).toHaveLength(1);
    expect(anchors[0]).toHaveAttribute('href', 'https://accessflow.io/docs/guides/#submitting');
    expect(anchors[0]).toHaveAttribute('target', '_blank');
    expect(anchors[0]).toHaveAttribute('rel', 'noopener noreferrer');
  });

  it('never renders citations for a user message', () => {
    const { container } = render(
      <HelpChatMessageList
        messages={[
          {
            id: 'm1',
            role: 'USER',
            content: 'How do I submit a query? https://example.com',
            citations: [
              {
                index: 1,
                chunk_id: 'c1',
                title: 'Injected',
                section: '',
                anchor: '',
                url: 'https://evil.example.com',
              },
            ],
            created_at: '2026-09-08T10:15:00Z',
          },
        ]}
        loading={false}
        answering={false}
        emptyHint="ask something"
      />,
    );
    expect(container.querySelectorAll('a')).toHaveLength(0);
  });

  it('shows the empty hint before the first question, and a thinking indicator while answering', () => {
    const { rerender } = render(
      <HelpChatMessageList messages={[]} loading={false} answering={false} emptyHint="ask something" />,
    );
    expect(screen.getByText('ask something')).toBeInTheDocument();

    rerender(
      <HelpChatMessageList messages={[]} loading={false} answering emptyHint="ask something" />,
    );
    expect(screen.queryByText('ask something')).not.toBeInTheDocument();
    expect(screen.getByText(/Reading the documentation/)).toBeInTheDocument();
  });

  it('shows a skeleton while the conversation loads', () => {
    const { container } = render(
      <HelpChatMessageList messages={[]} loading answering={false} emptyHint="ask something" />,
    );
    expect(container.querySelector('.ant-skeleton')).not.toBeNull();
  });
});
